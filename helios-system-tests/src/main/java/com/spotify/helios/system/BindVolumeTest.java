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

import com.google.common.collect.ImmutableList;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.TaskStatus;

import org.junit.Test;

import java.util.List;

import static com.spotify.docker.client.DockerClient.LogsParameter.STDERR;
import static com.spotify.docker.client.DockerClient.LogsParameter.STDOUT;
import static com.spotify.helios.common.descriptors.HostStatus.Status.UP;
import static com.spotify.helios.common.descriptors.TaskStatus.State.EXITED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

public class BindVolumeTest extends SystemTestBase {

  @Test
  public void test() throws Exception {
    try (final DockerClient docker = getNewDockerClient()) {
      // Start Helios agent, configured to bind host /proc into container /mnt/host-proc
      startDefaultMaster();
      startDefaultAgent(testHost(), "--bind", "/proc:/mnt/host-proc:ro");
      awaitHostStatus(testHost(), UP, LONG_WAIT_SECONDS, SECONDS);

      // Figure out the host kernel version
      final String hostKernelVersion = docker.info().kernelVersion();

      // Run a job that cat's /mnt/host-proc/version, which should be the host's version info
      final List<String> command = ImmutableList.of("cat", "/mnt/host-proc/version");
      final JobId jobId = createJob(testJobName, testJobVersion, BUSYBOX, command);
      deployJob(jobId, testHost());

      final TaskStatus taskStatus = awaitTaskState(jobId, testHost(), EXITED);

      {
        final String log;
        try (LogStream logs = docker.logs(taskStatus.getContainerId(), STDOUT, STDERR)) {
          log = logs.readFully();
        }

        // the kernel version from the host should be in the log
        assertThat(log, containsString(hostKernelVersion));
      }
    }
  }

}
