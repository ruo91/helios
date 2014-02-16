/**
 * Copyright (C) 2012 Spotify AB
 */

package com.spotify.helios.master;

import com.google.common.io.Resources;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListenableFuture;

import com.spotify.helios.master.metrics.ReportingResourceMethodDispatchAdapter;
import com.spotify.helios.master.resources.HostsResource;
import com.spotify.helios.master.resources.JobsResource;
import com.spotify.helios.master.resources.MastersResource;
import com.spotify.helios.master.resources.VersionResource;
import com.spotify.helios.servicescommon.DefaultZooKeeperClient;
import com.spotify.helios.servicescommon.ManagedStatsdReporter;
import com.spotify.helios.servicescommon.RiemannFacade;
import com.spotify.helios.servicescommon.RiemannSupport;
import com.spotify.helios.servicescommon.coordination.Paths;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClientProvider;
import com.spotify.helios.servicescommon.coordination.ZooKeeperModelReporter;
import com.spotify.helios.servicescommon.statistics.Metrics;
import com.spotify.helios.servicescommon.statistics.MetricsImpl;
import com.spotify.helios.servicescommon.statistics.NoopMetrics;
import com.spotify.nameless.client.Nameless;
import com.spotify.nameless.client.NamelessRegistrar;
import com.spotify.nameless.client.RegistrationHandle;
import com.yammer.dropwizard.config.ConfigurationException;
import com.yammer.dropwizard.config.Environment;
import com.yammer.dropwizard.config.RequestLogConfiguration;
import com.yammer.dropwizard.config.ServerFactory;
import com.yammer.dropwizard.lifecycle.ServerLifecycleListener;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.reporting.RiemannReporter;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.KeeperException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import ch.qos.logback.access.jetty.RequestLogImpl;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.zookeeper.CreateMode.EPHEMERAL;

/**
 * The Helios master service.
 */
public class MasterService extends AbstractIdleService {

  private static final Logger log = LoggerFactory.getLogger(MasterService.class);

  private final Server server;
  private final MasterConfig config;
  private final Environment environment;
  private final NamelessRegistrar registrar;
  private final RiemannFacade riemannFacade;
  private final RiemannReporter riemannReporter;
  private final ZooKeeperClient zooKeeperClient;
  private final MetricsRegistry metricsRegistry;

  private ListenableFuture<RegistrationHandle> namelessHandle;

  /**
   * Create a new service instance. Initializes the control interface and the worker.
   *
   * @param config The service configuration.
   */
  public MasterService(final MasterConfig config, final Environment environment)
      throws ConfigurationException {
    this.config = config;
    this.environment = environment;

    // Configure metrics
    // TODO (dano): do something with the riemann facade
    metricsRegistry = new MetricsRegistry();
    RiemannSupport riemannSupport = new RiemannSupport(metricsRegistry, config.getRiemannHostPort(),
        config.getName(), "helios-master");
    riemannFacade = riemannSupport.getFacade();
    log.info("Starting metrics");
    final Metrics metrics;
    if (config.isInhibitMetrics()) {
      metrics = new NoopMetrics();
      riemannReporter = null;
    } else {
      metrics = new MetricsImpl(metricsRegistry);
      metrics.start();
      riemannReporter = riemannSupport.getReporter();
      environment.manage(new ManagedStatsdReporter(config.getStatsdHostPort(), "helios-master"));
    }

    // Set up the master model
    this.zooKeeperClient = setupZookeeperClient(config);
    final MasterModel model = new ZooKeeperMasterModel(
        new ZooKeeperClientProvider(zooKeeperClient,
            new ZooKeeperModelReporter(riemannFacade, metrics.getZooKeeperMetrics())));

    if (config.getSite() != null) {
      this.registrar = config.getSite().equals("localhost")
                       ? Nameless.newRegistrar("tcp://localhost:4999")
                       : Nameless.newRegistrarForDomain(config.getSite());
    } else {
      this.registrar = null;
    }

    // Set up http server
    environment.addProvider(new ReportingResourceMethodDispatchAdapter(metrics.getMasterMetrics()));
    environment.addResource(new JobsResource(model, metrics.getMasterMetrics()));
    environment.addResource(new HostsResource(model));
    environment.addResource(new MastersResource(model));
    environment.addResource(new VersionResource());
    final RequestLogConfiguration requestLogConfiguration =
        config.getHttpConfiguration().getRequestLogConfiguration();
    requestLogConfiguration.getConsoleConfiguration().setEnabled(false);
    requestLogConfiguration.getSyslogConfiguration().setEnabled(false);
    requestLogConfiguration.getFileConfiguration().setEnabled(false);
    this.server = new ServerFactory(config.getHttpConfiguration(), environment.getName())
        .buildServer(environment);

    // Set up request logging
    final HandlerCollection handler = (HandlerCollection) server.getHandler();
    final RequestLogHandler requestLogHandler = new RequestLogHandler();
    final RequestLogImpl requestLog = new RequestLogImpl();
    requestLog.setQuiet(true);
    requestLog.setResource("/logback-access.xml");
    requestLogHandler.setRequestLog(requestLog);
    handler.addHandler(requestLogHandler);
    server.setHandler(handler);
  }

  @Override
  protected void startUp() throws Exception {
    logBanner();
    try {
      server.start();
      for (ServerLifecycleListener listener : environment.getServerListeners()) {
        listener.serverStarted(server);
      }
    } catch (Exception e) {
      log.error("Unable to start server, shutting down", e);
      server.stop();
    }

    if (registrar != null) {
      log.info("registering with nameless");
      namelessHandle = registrar.register("helios", "http",
                                          config.getHttpConfiguration().getPort());
    }
    if (riemannReporter != null) {
      riemannReporter.start(15, SECONDS);
    }
  }

  @Override
  protected void shutDown() throws Exception {
    server.stop();
    server.join();
    if (registrar != null) {
      if (namelessHandle.isDone()) {
        registrar.unregister(getUnchecked(namelessHandle));
      }
      registrar.shutdown();
    }
    zooKeeperClient.close();
    metricsRegistry.shutdown();
  }

  private void logBanner() {
    try {
      final String banner = Resources.toString(Resources.getResource("banner.txt"), UTF_8);
      log.info("\n{}", banner);
    } catch (IllegalArgumentException | IOException ignored) {
    }
    if (riemannReporter != null) {
      riemannReporter.shutdown();
    }
  }

  /**
   * Create a Zookeeper client and create the control and state nodes if needed.
   *
   * @param config The service configuration.
   * @return A zookeeper client.
   */
  private ZooKeeperClient setupZookeeperClient(final MasterConfig config) {
    final RetryPolicy zooKeeperRetryPolicy = new ExponentialBackoffRetry(1000, 3);
    final CuratorFramework curator = CuratorFrameworkFactory.newClient(
        config.getZooKeeperConnectString(), zooKeeperRetryPolicy);
    final ZooKeeperClient client = new DefaultZooKeeperClient(curator);
    client.start();

    // TODO (dano): move directory initialization elsewhere
    try {
      client.ensurePath(Paths.configAgents());
      client.ensurePath(Paths.configJobs());
      client.ensurePath(Paths.configJobRefs());
      client.ensurePath(Paths.statusAgents());
      client.ensurePath(Paths.statusMasters());
      client.ensurePath(Paths.historyJobs());

      final String upNode = Paths.statusMasterUp(config.getName());
      client.ensurePath(upNode, true);
      if (client.stat(upNode) != null) {
        client.delete(upNode);
      }
      client.createWithMode(upNode, EPHEMERAL);
    } catch (KeeperException e) {
      throw new RuntimeException("zookeeper initialization failed", e);
    }

    return client;
  }
}