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

import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.api.model.AbstractResource;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.core.spi.component.ioc.IoCComponentProvider;
import com.sun.jersey.server.spi.component.ResourceComponentProvider;
import com.sun.jersey.server.spi.component.ResourceComponentProviderFactory;

import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

public class SslSessionFactory implements ResourceComponentProviderFactory {
  private final HttpServletRequest request;

  public SslSessionFactory(@Context HttpServletRequest request) {
    this.request = request;
  }
  
  @Override
  public ResourceComponentProvider getComponentProvider(Class<?> c) {
    return new AbstractResourceComponentProvider(request);
  }

  @Override
  public ResourceComponentProvider getComponentProvider(IoCComponentProvider icp, Class<?> c) {
    return new AbstractResourceComponentProvider(request);
  }

  @Override
  public ComponentScope getScope(Class c) {
    return ComponentScope.PerRequest;
  }

  private static class AbstractResourceComponentProvider implements ResourceComponentProvider {
    private final HttpServletRequest request;

    public AbstractResourceComponentProvider(HttpServletRequest request) {
      this.request = request;
    }

    @Override
    public Object getInstance() {
      X509Certificate[] certs =
          // Comes from org.mortbay.http.JsseListener
             (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
      return Arrays.asList(certs);
    }

    @Override
    public void init(AbstractResource abstractResource) {
    }

    @Override
    public ComponentScope getScope() {
      return ComponentScope.PerRequest;
    }

    @Override
    public Object getInstance(HttpContext hc) {
      X509Certificate[] certs =
          // Comes from org.mortbay.http.JsseListener
          (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
      return Arrays.asList(certs); 
    }

    @Override
    public void destroy() {
      
    }
    
  }
}
