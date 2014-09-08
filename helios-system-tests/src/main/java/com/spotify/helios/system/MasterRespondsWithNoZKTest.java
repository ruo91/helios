/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.system;

import com.spotify.helios.ZooKeeperTestManager;
import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.HeliosException;
import com.spotify.helios.servicescommon.coordination.CuratorClientFactory;

import org.apache.curator.CuratorZookeeperClient;
import org.apache.curator.RetryLoop;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.GetChildrenBuilder;
import org.apache.curator.framework.listen.Listenable;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.EnsurePath;
import org.apache.zookeeper.KeeperException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class MasterRespondsWithNoZKTest extends SystemTestBase {

  @Override
  protected ZooKeeperTestManager zooKeeperTestManager() {
    final ZooKeeperTestManager testManager = mock(ZooKeeperTestManager.class);
    final MockCuratorClientFactory mockCuratorClientFactory = new MockCuratorClientFactory();
    final CuratorFramework curator = mockCuratorClientFactory.newClient(null, 0, 0, null, null);
    when(testManager.curator()).thenReturn(curator);

    when(testManager.connectString()).thenReturn("127.0.0.1");
    return testManager;
  }

  @Override
  protected void tearDownJobs() {}

  @Test
  public void test() throws Exception {

    startDefaultMasterDontWaitForZK(new MockCuratorClientFactory(), "--zk-connection-timeout", "1");

    final HeliosClient client = defaultClient();

    try {
      final String result = client.listMasters().get().get(0);

      fail("Exception should have been thrown, as ZK doesnt exist - got " + result);

    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof HeliosException);
    }

  }

  private static class MockCuratorClientFactory implements CuratorClientFactory {

    @Override
    public CuratorFramework newClient(String connectString,
                                      int sessionTimeoutMs,
                                      int connectionTimeoutMs,
                                      RetryPolicy retryPolicy,
                                      String namespace) {
      final CuratorFramework curator = mock(CuratorFramework.class);

      final RetryLoop retryLoop = mock(RetryLoop.class);
      when(retryLoop.shouldContinue()).thenReturn(false);

      final CuratorZookeeperClient czkClient = mock(CuratorZookeeperClient.class);
      when(czkClient.newRetryLoop()).thenReturn(retryLoop);

      when(curator.getZookeeperClient()).thenReturn(czkClient);

      @SuppressWarnings("unchecked")
      final Listenable<ConnectionStateListener> mockListener =
          (Listenable<ConnectionStateListener>) mock(Listenable.class);

      when(curator.getConnectionStateListenable()).thenReturn(mockListener);

      final GetChildrenBuilder builder = mock(GetChildrenBuilder.class);
      when(curator.getChildren()).thenReturn(builder);

      try {
        when(builder.forPath(anyString())).thenThrow(
            new KeeperException.ConnectionLossException());
      } catch (Exception e) {} // never throws
      when(curator.newNamespaceAwareEnsurePath(anyString())).thenReturn(mock(EnsurePath.class));

      return curator;
    }
  }
}
