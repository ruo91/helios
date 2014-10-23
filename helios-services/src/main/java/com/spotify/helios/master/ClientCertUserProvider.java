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

import com.spotify.helios.master.resources.RequestUser;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.AbstractHttpContextInjectable;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.security.Principal;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.ws.rs.ext.Provider;

@Provider
public class ClientCertUserProvider extends AbstractHttpContextInjectable<String>
    implements InjectableProvider<RequestUser, Type> {
  private static final Logger log = LoggerFactory.getLogger(ClientCertUserProvider.class);

  
  @Override
  public String getValue(HttpContext context) {
    Principal principal = context.getRequest().getUserPrincipal();
    if (principal == null) {
      log.warn("no principal provided");
      return null;
    }
    log.warn("principal.class {}", principal.getClass());
    log.warn("AuthScheme {}", context.getRequest().getAuthenticationScheme());
    log.warn("CERT {}", principal.getName());
    LdapName ln;
    try {
      ln = new LdapName(principal.getName());
    } catch (InvalidNameException e) {
      e.printStackTrace();
      return null;
    }
    for (Rdn rdn : ln.getRdns()) {
      log.warn("got rdn type: {}", rdn.getType());
      if (rdn.getType().equalsIgnoreCase("UID")) {
        log.warn("UID is: ()", rdn.getValue());
        return (String) rdn.getValue();
      }
    }
    log.warn("no UID found");
    return null;
  }

  @Override
  public Injectable<?> getInjectable(ComponentContext ctx, RequestUser ruAnnotation, Type type) {
    if (type.equals(String.class)) {
      return this;
    }
    return null;
  }

  @Override
  public ComponentScope getScope() {
    return ComponentScope.PerRequest;
  }
}
