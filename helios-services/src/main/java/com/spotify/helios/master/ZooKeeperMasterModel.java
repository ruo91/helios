/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.master;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spotify.helios.common.AgentDoesNotExistException;
import com.spotify.helios.common.HeliosException;
import com.spotify.helios.common.JobAlreadyDeployedException;
import com.spotify.helios.common.JobDoesNotExistException;
import com.spotify.helios.common.JobExistsException;
import com.spotify.helios.common.JobNotDeployedException;
import com.spotify.helios.common.JobPortAllocationConflictException;
import com.spotify.helios.common.JobStillInUseException;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.descriptors.AgentStatus;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.Descriptor;
import com.spotify.helios.common.descriptors.Goal;
import com.spotify.helios.common.descriptors.HostInfo;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.PortMapping;
import com.spotify.helios.common.descriptors.RuntimeInfo;
import com.spotify.helios.common.descriptors.Task;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.protocol.JobStatus;
import com.spotify.helios.common.protocol.TaskStatusEvent;
import com.spotify.helios.servicescommon.coordination.Node;
import com.spotify.helios.servicescommon.coordination.Paths;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClientProvider;
import com.spotify.helios.servicescommon.coordination.ZooKeeperOperation;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.data.Stat;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static com.google.common.base.Optional.fromNullable;
import static com.google.common.collect.Lists.reverse;
import static com.spotify.helios.common.descriptors.AgentStatus.Status.DOWN;
import static com.spotify.helios.common.descriptors.AgentStatus.Status.UP;
import static com.spotify.helios.common.descriptors.Descriptor.parse;
import static com.spotify.helios.common.descriptors.Goal.UNDEPLOY;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.check;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.create;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.delete;
import static com.spotify.helios.servicescommon.coordination.ZooKeeperOperations.set;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public class ZooKeeperMasterModel implements MasterModel {

  public static final class EventComparator implements Comparator<TaskStatusEvent> {

    @Override
    public int compare(TaskStatusEvent arg0, TaskStatusEvent arg1) {
      if (arg1.getTimestamp() > arg0.getTimestamp()) {
        return -1;
      } else if (arg1.getTimestamp() == arg0.getTimestamp()) {
        return 0;
      } else {
        return 1;
      }
    }
  }

  private static final EventComparator EVENT_COMPARATOR = new EventComparator();

  private static final Logger log = LoggerFactory.getLogger(ZooKeeperMasterModel.class);

  public static final Map<JobId, TaskStatus> EMPTY_STATUSES = emptyMap();

  private final ZooKeeperClientProvider provider;

  public ZooKeeperMasterModel(final ZooKeeperClientProvider provider) {
    this.provider = provider;
  }

  @Override
  public void addAgent(final String agent) throws HeliosException {
    log.debug("adding agent: {}", agent);
    final ZooKeeperClient client = provider.get("addAgent");
    try {
      // TODO (dano): this code is replicated in AgentRegistrar

      // This would've been nice to do in a transaction but PathChildrenCache ensures paths
      // so we can't know what paths already exist so assembling a suitable transaction is too
      // painful.
      client.ensurePath(Paths.configAgent(agent));
      client.ensurePath(Paths.configAgent(agent));
      client.ensurePath(Paths.configAgentJobs(agent));
      client.ensurePath(Paths.configAgentPorts(agent));
      client.ensurePath(Paths.statusAgent(agent));
      client.ensurePath(Paths.statusAgentJobs(agent));

      // Finish registration by creating the id node last
      client.ensurePath(Paths.configAgentId(agent));
    } catch (Exception e) {
      throw new HeliosException("adding agent " + agent + " failed", e);
    }
  }

  @Override
  public List<String> getAgents() throws HeliosException {
    try {
      return provider.get("getAgents").getChildren(Paths.configAgents());
    } catch (KeeperException.NoNodeException e) {
      return emptyList();
    } catch (KeeperException e) {
      throw new HeliosException("listing agents failed", e);
    }
  }

  @Override
  public ImmutableList<String> getRunningMasters() throws HeliosException {
    try {
      return ImmutableList.copyOf(
          Iterables.filter(provider.get("getRunningMasters").getChildren(Paths.statusMaster()),
                           new Predicate<String>() {
                             @Override
                             public boolean apply(String masterName) {
                               return loadMasterUp(masterName);
                             }
                           }));
    } catch (KeeperException.NoNodeException e) {
      return ImmutableList.of();
    } catch (KeeperException e) {
      throw new HeliosException("listing masters failed", e);
    }
  }

  private boolean loadMasterUp(String master) {
    try {
      provider.get("loadMasterUp").getData(Paths.statusMasterUp(master));
      return true;
    } catch (KeeperException e) {
      return false;
    }
  }

  @Override
  public void removeAgent(final String agent) throws HeliosException {
    try {
      // TODO (dano): This might fail in a race with someone concurrently deploying jobs to this agent. Do we care?
      final ZooKeeperClient client = provider.get("removeAgent");
      final List<String> nodes = reverse(client.listRecursive(Paths.configAgent(agent)));
      client.transaction(delete(nodes));
    } catch (NoNodeException e) {
      throw new AgentDoesNotExistException(agent);
    } catch (KeeperException e) {
      throw new HeliosException(e);
    }
  }

  @Override
  public void addJob(final Job job) throws HeliosException {
    log.debug("adding job: {}", job);
    final JobId id = job.getId();
    try {
      final ZooKeeperClient client = provider.get("addJob");
      client.ensurePath(Paths.historyJob(id));
      client.transaction(create(Paths.configJob(id), job),
                         create(Paths.configJobRefShort(id), id),
                         create(Paths.configJobAgents(id)));
    } catch (final NodeExistsException e) {
      throw new JobExistsException(id.toString());
    } catch (final KeeperException e) {
      throw new HeliosException("adding job " + job + " failed", e);
    }
  }

  @Override
  public List<TaskStatusEvent> getJobHistory(final JobId jobId) throws HeliosException {
    final Job descriptor = getJob(jobId);
    if (descriptor == null) {
      throw new JobDoesNotExistException(jobId);
    }
    final ZooKeeperClient client = provider.get("getJobHistory");
    final List<String> agents;
    try {
      agents = client.getChildren(Paths.historyJobAgents(jobId));
    } catch (NoNodeException e) {
      return ImmutableList.<TaskStatusEvent>of();
    } catch (KeeperException e) {
      throw Throwables.propagate(e);
    }

    final List<TaskStatusEvent> jsEvents = Lists.newArrayList();

    for (String agent : agents) {
      final List<String> events;
      try {
        events = client.getChildren(Paths.historyJobAgentEvents(jobId, agent));
      } catch (KeeperException e) {
        throw Throwables.propagate(e);
      }

      for (String event : events) {
        try {
          byte[] data = client.getData(Paths.historyJobAgentEventsTimestamp(
              jobId, agent, Long.valueOf(event)));
          final TaskStatus status = Json.read(data, TaskStatus.class);
          jsEvents.add(new TaskStatusEvent(status, Long.valueOf(event), agent));
        } catch (KeeperException | IOException e) {
          throw Throwables.propagate(e);
        }
      }
    }

    return Ordering.from(EVENT_COMPARATOR).sortedCopy(jsEvents);
  }

  @Override
  public Job getJob(final JobId id) throws HeliosException {
    log.debug("getting job: {}", id);
    final String path = Paths.configJob(id);
    final ZooKeeperClient client = provider.get("getJob");
    try {
      final byte[] data = client.getData(path);
      return Descriptor.parse(data, Job.class);
    } catch (NoNodeException e) {
      // Return null to indicate that the job does not exist
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosException("getting job " + id + " failed", e);
    }
  }

  @Override
  public Map<JobId, Job> getJobs() throws HeliosException {
    log.debug("getting jobs");
    final String folder = Paths.configJobs();
    final ZooKeeperClient client = provider.get("getJobs");
    try {
      final List<String> ids;
      try {
        ids = client.getChildren(folder);
      } catch (NoNodeException e) {
        return Maps.newHashMap();
      }
      final Map<JobId, Job> descriptors = Maps.newHashMap();
      for (final String id : ids) {
        final JobId jobId = JobId.fromString(id);
        final String path = Paths.configJob(jobId);
        final byte[] data = client.getData(path);
        final Job descriptor = parse(data, Job.class);
        descriptors.put(descriptor.getId(), descriptor);
      }
      return descriptors;
    } catch (KeeperException | IOException e) {
      throw new HeliosException("getting jobs failed", e);
    }
  }

  @Override
  public JobStatus getJobStatus(final JobId jobId) throws HeliosException {
    final Job job = getJob(jobId);
    if (job == null) {
      return null;
    }

    final List<String> agents;
    try {
      // TODO (dano): this will list all agents that the job is deployed to, maybe we should list all agents that are reporting that they are running this job
      agents = listJobAgents(jobId);
    } catch (JobDoesNotExistException e) {
      return null;
    }

    final ImmutableMap.Builder<String, TaskStatus> taskStatuses = ImmutableMap.builder();
    for (final String agent : agents) {
      final TaskStatus taskStatus = getTaskStatus(agent, jobId);
      if (taskStatus != null) {
        taskStatuses.put(agent, taskStatus);
      }
    }

    return JobStatus.newBuilder()
        .setJob(job)
        .setDeployedHosts(ImmutableSet.copyOf(agents))
        .setTaskStatuses(taskStatuses.build())
        .build();
  }

  private List<String> listJobAgents(final JobId jobId) throws HeliosException {
    final List<String> agents;
    final String agentsPath = Paths.configJobAgents(jobId);
    final ZooKeeperClient client = provider.get("listJobAgents");
    try {
      agents = client.getChildren(agentsPath);
    } catch (NoNodeException e) {
      throw new JobDoesNotExistException(jobId);
    } catch (KeeperException e) {
      throw new HeliosException("failed listing agents for job: " + jobId, e);
    }
    return agents;
  }

  @Override
  public Job removeJob(final JobId id) throws HeliosException {
    log.debug("removing job: id={}", id);
    final Job old = getJob(id);
    final ZooKeeperClient client = provider.get("removeJob");
    try {
      client.transaction(delete(Paths.configJobAgents(id)),
                         delete(Paths.configJobRefShort(id)),
                         delete(Paths.configJob(id)));
    } catch (final NoNodeException e) {
      throw new JobDoesNotExistException(id);
    } catch (final KeeperException.NotEmptyException e) {
      final List<String> agents = listJobAgents(id);
      throw new JobStillInUseException(id, agents);
    } catch (final KeeperException e) {
      throw new HeliosException("removing job " + id + " failed", e);
    }

    return old;
  }

  @Override
  public void deployJob(final String agent, final Deployment deployment)
      throws HeliosException {
    deployJobRetry(agent, deployment, 0);
  }

  private void deployJobRetry(final String agent, final Deployment deployment, int count)
      throws HeliosException {
    if (count == 3) {
      throw new HeliosException("3 failures (possibly concurrent modifications) while " +
                                "deploying. Giving up.");
    }
    log.debug("adding deployment: agent={}, job={}", agent, deployment);

    final JobId id = deployment.getJobId();
    final Job job = getJob(id);

    if (job == null) {
      throw new JobDoesNotExistException(id);
    }

    final String jobPath = Paths.configJob(id);
    final String taskPath = Paths.configAgentJob(agent, id);

    final List<Integer> staticPorts = staticPorts(job);
    final Map<String, byte[]> portNodes = Maps.newHashMap();
    final byte[] idJson = id.toJsonBytes();
    for (final int port : staticPorts) {
      final String path = Paths.configAgentPort(agent, port);
      portNodes.put(path, idJson);
    }

    final Task task = new Task(job, deployment.getGoal());
    List<ZooKeeperOperation> operations = Lists.newArrayList(
        check(jobPath), create(portNodes), create(Paths.configJobAgent(id, agent)));

    final ZooKeeperClient client = provider.get("deployJob");
   // Attempt to read a task here.  If it's goal is UNDEPLOY, it's as good as not existing
    try {
      Node existing = client.getNode(taskPath);
      byte[] bytes = existing.getBytes();
      Task readTask = Json.read(bytes, Task.class);
      if (readTask.getGoal() != Goal.UNDEPLOY) {
        throw new JobAlreadyDeployedException(agent, id);
      }
      operations.add(check(taskPath, existing.getStat().getVersion()));
      operations.add(set(taskPath, task));
    } catch (NoNodeException e) {
      operations.add(create(taskPath, task));
    } catch (KeeperException e) {
      throw new HeliosException("reading existing task description failed", e);
    } catch (IOException e) {
      throw new HeliosException("parsing existing task description failed", e);
    }

    // TODO (dano): Failure handling is racy wrt agent and job modifications. Probably rare, but still.
    try {
      client.transaction(operations);
    } catch (NoNodeException e) {
      // Either the job or the agent went away
      assertJobExists(id);
      assertAgentExists(agent);
      // Retry
      deployJobRetry(agent, deployment, count + 1);
    } catch (NodeExistsException e) {
      try {
        // Check if the job was already deployed
        if (client.stat(taskPath) != null) {
          throw new JobAlreadyDeployedException(agent, id);
        }
      } catch (KeeperException ex) {
        throw new HeliosException("checking job deployment failed", e);
      }

      // Check for static port collisions
      for (final int port : staticPorts) {
        final String path = Paths.configAgentPort(agent, port);
        try {
          if (client.stat(path) == null) {
            continue;
          }
          final byte[] b = client.getData(path);
          final JobId existingJobId = parse(b, JobId.class);
          throw new JobPortAllocationConflictException(id, existingJobId, agent, port);
        } catch (KeeperException | IOException ex) {
          throw new HeliosException("checking port allocations failed", e);
        }
      }

      // Catch all for logic and ephemeral issues
      throw new HeliosException("deploying job failed", e);
    } catch (Exception e) {
      throw new HeliosException("deploying job failed", e);
    }
  }

  private void assertJobExists(final JobId id) throws HeliosException {
    final ZooKeeperClient client = provider.get("assertJobExists");
    try {
      final String path = Paths.configJob(id);
      if (client.stat(path) == null) {
        throw new JobDoesNotExistException(id);
      }
    } catch (KeeperException e) {
      throw new HeliosException("checking job existence failed", e);
    }
  }

  private List<Integer> staticPorts(final Job job) {
    final List<Integer> staticPorts = Lists.newArrayList();
    for (final PortMapping portMapping : job.getPorts().values()) {
      if (portMapping.getExternalPort() != null) {
        staticPorts.add(portMapping.getExternalPort());
      }
    }
    return staticPorts;
  }

  @Override
  public void updateDeployment(final String agent, final Deployment deployment)
      throws HeliosException {
    log.debug("updating deployment: agent={}, job={}", agent, deployment);

    final JobId jobId = deployment.getJobId();
    final Job descriptor = getJob(jobId);

    if (descriptor == null) {
      throw new JobDoesNotExistException(jobId);
    }

    assertAgentExists(agent);
    assertTaskExists(agent, deployment.getJobId());

    final String path = Paths.configAgentJob(agent, jobId);
    final Task task = new Task(descriptor, deployment.getGoal());
    final ZooKeeperClient client = provider.get("updateDeployment");
    try {
      client.setData(path, task.toJsonBytes());
    } catch (Exception e) {
      throw new HeliosException("updating job on agent failed", e);
    }
  }

  private void assertAgentExists(String agent) throws HeliosException {
    final ZooKeeperClient client = provider.get("assertAgentExists");
    try {
      client.getData(Paths.configAgent(agent));
    } catch (NoNodeException e) {
      throw new AgentDoesNotExistException(agent, e);
    } catch (KeeperException e) {
      throw new HeliosException(e);
    }
  }

  private void assertTaskExists(String agent, JobId jobId) throws HeliosException {
    final ZooKeeperClient client = provider.get("assertTaskExists");
    try {
      client.getData(Paths.configAgentJob(agent, jobId));
    } catch (NoNodeException e) {
      throw new JobNotDeployedException(agent, jobId, e);
    } catch (KeeperException e) {
      throw new HeliosException(e);
    }
  }

  @Override
  public Deployment getDeployment(final String agent, final JobId jobId)
      throws HeliosException {
    final String path = Paths.configAgentJob(agent, jobId);
    final ZooKeeperClient client = provider.get("getDeployment");
    try {
      final byte[] data = client.getData(path);
      final Task task = parse(data, Task.class);
      if (task.getGoal() == UNDEPLOY) {
        return null;
      }
      return Deployment.of(jobId, task.getGoal());
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosException("getting slave container failed", e);
    }
  }

  @Override
  public AgentStatus getAgentStatus(final String agent)
      throws HeliosException {
    final Stat stat;
    final ZooKeeperClient client = provider.get("getAgentStatus");

    try {
      stat = client.exists(Paths.configAgentId(agent));
    } catch (KeeperException e) {
      throw new HeliosException("Failed to check agent status", e);
    }

    if (stat == null) {
      return null;
    }

    final boolean up = checkAgentUp(agent);
    final HostInfo hostInfo = getAgentHostInfo(agent);
    final RuntimeInfo runtimeInfo = getAgentRuntimeInfo(agent);
    final Map<JobId, Deployment> tasks = getTasks(agent);

    final Map<JobId, Deployment> jobs = Maps.filterEntries(tasks,
        new Predicate<Entry<JobId, Deployment>>() {
          @Override public boolean apply(Entry<JobId, Deployment> entry) {
            return entry.getValue().getGoal() != UNDEPLOY;
          }
        });
    final Map<JobId, TaskStatus> statuses = getTaskStatuses(agent);
    final Map<String, String> environment = getEnvironment(agent);

    return AgentStatus.newBuilder()
        .setJobs(jobs)
        .setStatuses(fromNullable(statuses).or(EMPTY_STATUSES))
        .setHostInfo(hostInfo)
        .setRuntimeInfo(runtimeInfo)
        .setStatus(up ? UP : DOWN)
        .setEnvironment(environment)
        .build();
  }

  private <T> T getAgentStatusData(String path, TypeReference<T> type, String thing)
      throws HeliosException {
    final ZooKeeperClient client = provider.get("getAgentStatusData_" + thing);
    try {
      final byte[] data = client.getData(path);
      return Json.read(data, type);
    } catch (NoNodeException e) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosException("getting agent " + thing + " info failed", e);
    }
  }

  private Map<String, String> getEnvironment(final String agent) throws HeliosException {
    return getAgentStatusData(Paths.statusAgentEnvVars(agent),
                              new TypeReference<Map<String,String>>(){},
                              "environment");
  }

  private RuntimeInfo getAgentRuntimeInfo(final String agent) throws HeliosException {
    return getAgentStatusData(Paths.statusAgentRuntimeInfo(agent),
                              new TypeReference<RuntimeInfo>(){},
                              "runtime info");
  }

  private HostInfo getAgentHostInfo(final String agent) throws HeliosException {
    return getAgentStatusData(Paths.statusAgentHostInfo(agent),
                              new TypeReference<HostInfo>(){},
                              "host info");
  }

  private boolean checkAgentUp(final String agent) throws HeliosException {
    final ZooKeeperClient client = provider.get("checkAgentUp");
    try {
      final Stat stat = client.stat(Paths.statusAgentUp(agent));
      return stat != null;
    } catch (KeeperException e) {
      throw new HeliosException("getting agent up status failed", e);
    }
  }

  private Map<JobId, TaskStatus> getTaskStatuses(final String agent) throws HeliosException {
    final Map<JobId, TaskStatus> statuses = Maps.newHashMap();
    final List<String> jobIdStrings;
    final String folder = Paths.statusAgentJobs(agent);
    final ZooKeeperClient client = provider.get("getTaskStatuses");
    try {
      jobIdStrings = client.getChildren(folder);
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (KeeperException e) {
      throw new HeliosException("List tasks for agent failed: " + agent, e);
    }

    for (final String jobIdString : jobIdStrings) {
      final JobId jobId = JobId.fromString(jobIdString);
      final TaskStatus status = getTaskStatus(agent, jobId);

      if (status != null) {
        statuses.put(jobId, status);
      } else {
        log.debug("Task {} status missing for agent {}", jobId, agent);
      }
    }

    return statuses;
  }

  @Nullable
  private TaskStatus getTaskStatus(final String agent, final JobId jobId)
      throws HeliosException {
    final String containerPath = Paths.statusAgentJob(agent, jobId);
    final ZooKeeperClient client = provider.get("getTaskStatus");
    try {
      final byte[] data = client.getData(containerPath);
      return parse(data, TaskStatus.class);
    } catch (NoNodeException ignored) {
      return null;
    } catch (KeeperException | IOException e) {
      throw new HeliosException("Getting task " + jobId + "for agent " + agent + " failed", e);
    }
  }

  private Map<JobId, Deployment> getTasks(final String agent) throws HeliosException {
    final Map<JobId, Deployment> jobs = Maps.newHashMap();
    final ZooKeeperClient client = provider.get("getTasks");
    try {
      final String folder = Paths.configAgentJobs(agent);
      final List<String> jobIds;
      try {
        jobIds = client.getChildren(folder);
      } catch (KeeperException.NoNodeException e) {
        return null;
      }

      for (final String jobIdString : jobIds) {
        final JobId jobId = JobId.fromString(jobIdString);
        final String containerPath = Paths.configAgentJob(agent, jobId);
        try {
          final byte[] data = client.getData(containerPath);
          final Task task = parse(data, Task.class);
          if (task.getGoal() == UNDEPLOY) {
            continue;
          }
          jobs.put(jobId, Deployment.of(jobId, task.getGoal()));
        } catch (KeeperException.NoNodeException ignored) {
          log.debug("deploymenty config node disappeared: {}", jobIdString);
        }
      }
    } catch (KeeperException | IOException e) {
      throw new HeliosException("getting deployment config failed", e);
    }

    return jobs;
  }

  @Override
  public Deployment undeployJob(final String agent, final JobId jobId)
      throws HeliosException {
    log.debug("removing deployment: agent={}, job={}", agent, jobId);

    assertAgentExists(agent);

    final Deployment deployment = getDeployment(agent, jobId);
    if (deployment == null) {
      throw new JobDoesNotExistException(String.format("Job [%s] does not exist on agent [%s]",
                                                       jobId, agent));
    }

    updateDeployment(agent, Deployment.of(jobId, Goal.UNDEPLOY));
    List<ZooKeeperOperation> operations = Lists.newArrayList(
        delete(Paths.configJobAgent(jobId, agent)));
    // This could be deleted by the agent when it undeploys, though they could stick around
    // fairly indefinitely as we ignore/filter them as appropriate.
    //        delete(Paths.configAgentJob(agent, jobId)),

    final Job job = getJob(jobId);
    final List<Integer> staticPorts = staticPorts(job);
    for (int port : staticPorts) {
        operations.add(delete(Paths.configAgentPort(agent, port)));
    }
    final ZooKeeperClient client = provider.get("undeployJob");
    try {
      client.transaction(operations);
    } catch (KeeperException e) {
      throw new HeliosException("Removing deployment failed", e);
    }
    return deployment;
  }
}