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

import com.google.common.base.Joiner;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

public class SslIdentityFilter implements ContainerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(SslIdentityFilter.class);

  @Context public HttpServletRequest servletRequest;
  
  @Override
  public ContainerRequest filter(final ContainerRequest request) {
    log.warn("servlet erquest is {}", servletRequest);
    
    
    

    X509Certificate[] certs =
     // Comes from org.mortbay.http.JsseListener
        (X509Certificate[]) servletRequest.getAttribute("javax.servlet.request.X509Certificate");

    final X509Certificate firstCert;
    if (certs != null) {
      firstCert = certs[0];
      for (int n = 0; n < certs.length; n++) {
        final X509Certificate x509Certificate = certs[n];
        log.warn("SSL Client cert subject: " + x509Certificate.getSubjectDN().toString());
        log.warn("kind of thing {}", x509Certificate.getSubjectDN().getClass());
        log.warn("Issuer {}", x509Certificate.getIssuerX500Principal());
      }
    } else {
      return request;
    }

    request.setSecurityContext(new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return firstCert.getSubjectDN();
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "CERT_AUTH";
      }
      
    });
    return request;
  }

}
