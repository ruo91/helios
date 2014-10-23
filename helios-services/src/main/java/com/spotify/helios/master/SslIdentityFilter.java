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

package com.spotify.helios.master;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

import java.security.cert.X509Certificate;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

public class SslIdentityFilter implements ContainerRequestFilter {
  @Context public HttpServletRequest servletRequest;
  
  @Override
  public ContainerRequest filter(final ContainerRequest request) {
    final X509Certificate[] certs =
        // Don't you love magical constants? --> Comes from org.mortbay.http.JsseListener
        (X509Certificate[]) servletRequest.getAttribute("javax.servlet.request.X509Certificate");

    final X509Certificate firstCert;
    if (certs == null) {
      return request;
    } 
    firstCert = certs[0];

    request.setSecurityContext(new SslSecurityContextImpl(firstCert));
    return request;
  }
}
