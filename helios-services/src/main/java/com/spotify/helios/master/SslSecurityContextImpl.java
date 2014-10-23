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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.security.cert.X509Certificate;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.ws.rs.core.SecurityContext;

public class SslSecurityContextImpl implements SecurityContext {
  private static final Logger log = LoggerFactory.getLogger(SslSecurityContextImpl.class);

  private final X509Certificate cert;

  SslSecurityContextImpl(final X509Certificate cert) {
    this.cert = cert;
  }

  @Override
  public Principal getUserPrincipal() {
    return cert.getSubjectDN();
  }

  @Override
  public boolean isSecure() {
    return true;
  }

  @Override
  public String getAuthenticationScheme() {
    return "CERT_AUTH";
  }

  @Override
  public boolean isUserInRole(final String role) {
    final LdapName ln;
    try {
      ln = new LdapName(cert.getSubjectDN().getName());
    } catch (InvalidNameException e) {
      e.printStackTrace();
      return false;
    }
    for (final Rdn rdn : ln.getRdns()) {
      log.warn("got rdn type: {}", rdn.getType());
      if (rdn.getType().equalsIgnoreCase("DC")) {
        log.warn("ROLE is: ()", rdn.getValue());
        if (role.equals(rdn.getValue().toString())) {
          return true;
        }
      }
    }
    return false;
  }
}
