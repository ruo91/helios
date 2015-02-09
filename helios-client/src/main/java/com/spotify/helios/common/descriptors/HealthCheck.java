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

package com.spotify.helios.common.descriptors;

import com.google.common.base.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.jetbrains.annotations.Nullable;


/**
 * Represents an endpoint in the container on which Helios can perform a health check.
 *
 * A typical JSON representation might be:
 * <pre>
 * {
 *   "portName" : "http-admin",
 *   "urlPath" : "/healthcheck/endpoint",
 *   "timeout" : 120
 * }
 * </pre>
 */
public class HealthCheck extends Descriptor {

  public static final int DEFAULT_TIMEOUT_SECONDS = 120;

  private final String portName;
  private final String urlPath;
  private final int timeout;

  public HealthCheck(@JsonProperty("portName") final String portName,
                     @JsonProperty("urlPath") final String urlPath,
                     @JsonProperty("timeout") final int timeout) {
    this.portName = portName;
    this.urlPath = urlPath;
    this.timeout = timeout;
  }

  public HealthCheck(final String portName, final String urlPath) {
    this.portName = portName;
    this.urlPath = urlPath;
    this.timeout = DEFAULT_TIMEOUT_SECONDS;
  }

  public String getPortName() {
    return portName;
  }

  @Nullable
  public String getUrlPath() {
    return urlPath;
  }

  public int getTimeout() {
    return timeout;
  }

  public static HealthCheck of(final String portName, final String urlPath) {
    return new HealthCheck(portName, urlPath);
  }

  public static HealthCheck of(final String portName, final String urlPath, final int timeout) {
    return new HealthCheck(portName, urlPath, timeout);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final HealthCheck that = (HealthCheck) o;

    if (portName != null ? !portName.equals(that.portName) : that.portName != null) {
      return false;
    }
    if (urlPath != null ? !urlPath.equals(that.urlPath) : that.urlPath != null) {
      return false;
    }
    if (timeout != that.timeout) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = timeout;
    result = 31 * result + (portName != null ? portName.hashCode() : 0);
    result = 31 * result + (urlPath != null ? urlPath.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("portName", portName)
        .add("urlPath", urlPath)
        .add("timeout", timeout)
        .toString();
  }
}
