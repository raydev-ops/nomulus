// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.domain.registry.util.NetworkUtils.getCanonicalHostName;

import com.google.common.base.Throwables;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Callables;
import com.google.common.util.concurrent.SimpleTimeLimiter;

import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServlet;

/**
 * HTTP server that serves static content and handles servlet requests in the calling thread.
 *
 * <p>Using this server is similar to to other server classes, in that it has {@link #start()} and
 * {@link #stop()} methods. However a {@link #process()} method was added, which is used to process
 * requests made to servlets (not static files) in the calling thread.
 *
 * <p><b>Note:</b> This server is intended for development purposes. For the love all that is good,
 * do not make this public facing.
 *
 * <h3>Implementation Details</h3>
 *
 * <p>Jetty6 is multithreaded and provides no mechanism for controlling which threads execute your
 * requests. HttpServer solves this problem by wrapping all the servlets provided to the constructor
 * inside {@link ServletWrapperDelegatorServlet}. When requests come in, a {@link FutureTask} will
 * be sent back to this class using a {@link LinkedBlockingDeque} message queue. Those messages are
 * then consumed by the {@code process()} method.
 *
 * <p>The reason why this is necessary is because the App Engine local testing services (created by
 * {@code LocalServiceTestHelper}) only apply to a single thread (probably to allow multi-threaded
 * tests). So when Jetty creates random threads to handle requests, they won't have access to the
 * datastore and other stuff.
 */
public final class TestServer {

  private static final int DEFAULT_PORT = 80;
  private static final String CONTEXT_PATH = "/";
  private static final int STARTUP_TIMEOUT_MS = 5000;
  private static final int SHUTDOWN_TIMEOUT_MS = 5000;

  private final HostAndPort urlAddress;
  private final Server server = new Server();
  private final BlockingQueue<FutureTask<Void>> requestQueue = new LinkedBlockingDeque<>();

  /**
   * Creates a new instance, but does not begin serving.
   *
   * @param address socket bind address
   * @param runfiles map of server paths to local directories or files, to be served statically
   * @param routes list of servlet endpoints
   */
  public TestServer(HostAndPort address, Map<String, Path> runfiles, Iterable<Route> routes) {
    urlAddress = createUrlAddress(address);
    server.addConnector(createConnector(address));
    server.addHandler(createHandler(runfiles, routes));
  }

  /** Starts the HTTP server in a new thread and returns once it's online. */
  public void start() {
    try {
      server.start();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
    UrlChecker.waitUntilAvailable(getUrl("/healthz"), STARTUP_TIMEOUT_MS);
  }

  /**
   * Processes a single servlet request.
   *
   * <p>This method should be called from within a loop.
   *
   * @throws InterruptedException if this thread was interrupted while waiting for a request.
   */
  public void process() throws InterruptedException {
    requestQueue.take().run();
  }

  /**
   * Adds a fake entry to this server's event loop.
   *
   * <p>This is useful in situations when a random thread wants {@link #process()} to return in the
   * main event loop, for post-request processing.
   */
  public void ping() {
    requestQueue.add(new FutureTask<>(Callables.<Void>returning(null)));
  }

  /** Stops the HTTP server. */
  public void stop() {
    try {
      new SimpleTimeLimiter().callWithTimeout(new Callable<Void>() {
        @Nullable
        @Override
        public Void call() throws Exception {
          server.stop();
          return null;
        }
      }, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS, true);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /** Returns a URL that can be used to communicate with this server. */
  public URL getUrl(String path) {
    checkArgument(path.startsWith("/"), "Path must start with a slash: %s", path);
    try {
      return new URL(String.format("http://%s%s", urlAddress, path));
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  private Context createHandler(Map<String, Path> runfiles, Iterable<Route> routes) {
    Context context = new Context(server, CONTEXT_PATH, Context.SESSIONS);
    context.addServlet(new ServletHolder(HealthzServlet.class), "/healthz");
    for (Map.Entry<String, Path> runfile : runfiles.entrySet()) {
      context.addServlet(
          StaticResourceServlet.create(runfile.getKey(), runfile.getValue()),
          runfile.getKey());
    }
    for (Route route : routes) {
      context.addServlet(new ServletHolder(wrapServlet(route.servletClass())), route.path());
    }
    ServletHolder holder = new ServletHolder(DefaultServlet.class);
    holder.setInitParameter("aliases", "1");
    context.addServlet(holder, "/*");
    return context;
  }

  private HttpServlet wrapServlet(Class<? extends HttpServlet> servletClass) {
    return new ServletWrapperDelegatorServlet(servletClass, requestQueue);
  }

  private static Connector createConnector(HostAndPort address) {
    SocketConnector connector = new SocketConnector();
    connector.setHost(address.getHostText());
    connector.setPort(address.getPortOrDefault(DEFAULT_PORT));
    return connector;
  }

  /** Converts a bind address into an address that other machines can use to connect here. */
  private static HostAndPort createUrlAddress(HostAndPort address) {
    if (address.getHostText().equals("::") || address.getHostText().equals("0.0.0.0")) {
      return address.getPortOrDefault(DEFAULT_PORT) == DEFAULT_PORT
          ? HostAndPort.fromHost(getCanonicalHostName())
          : HostAndPort.fromParts(getCanonicalHostName(), address.getPort());
    } else {
      return address.getPortOrDefault(DEFAULT_PORT) == DEFAULT_PORT
          ? HostAndPort.fromHost(address.getHostText())
          : address;
    }
  }
}
