package com.qa.platform.net;

import com.qa.platform.common.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;

/**
 * {@code doctor --env <env>}: checks that this machine can run the tests against a company environment
 * before anybody waits for a full {@code mvn test}. Never prints secret values, only the names of missing ones.
 *
 * <ol>
 *   <li>Java 17+, Maven on the PATH (the approval UI's Execute button needs it)</li>
 *   <li>config/env/&lt;env&gt;.properties exists; no placeholder base URI; every ${VAR} it references is set</li>
 *   <li>trust store / client certificate load with their passwords</li>
 *   <li>every base URI and OAuth token URL answers over HTTP(S), through the proxy when configured;
 *       DNS, connection, TLS and timeout failures each get their own hint</li>
 * </ol>
 */
public class EnvironmentDoctor {

  public enum Level { OK, WARN, FAIL }

  public record Check(String name, Level level, String detail, String hint) {}

  public record Report(String env, List<Check> checks) {
    public boolean ok() { return checks.stream().noneMatch(c -> c.level() == Level.FAIL); }
  }

  private static final Pattern VAR = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

  public Report run(String env) {
    List<Check> out = new ArrayList<>();
    out.add(java());
    out.add(maven());
    Path file = Workspace.CONFIG.resolve("env").resolve(env + ".properties");
    if (!Files.exists(file)) {
      out.add(new Check("config file", Level.FAIL, file + " not found",
          "Copy an existing one: ./scripts/new-env.sh " + env));
      return new Report(env, out);
    }
    Properties props = new Properties();
    Path local = file.resolveSibling(env + ".local.properties");
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
      if (Files.exists(local)) try (InputStream l = Files.newInputStream(local)) { props.load(l); }
    } catch (IOException e) {
      out.add(new Check("config file", Level.FAIL, e.getMessage(), null));
      return new Report(env, out);
    }
    out.add(new Check("config file", Level.OK, file + (Files.exists(local) ? " + " + local.getFileName() : ""), null));

    // ${VAR} references with no value in this shell (names only, values are secrets)
    Set<String> missing = new TreeSet<>();
    for (String k : props.stringPropertyNames()) {
      if (System.getProperty(k) != null || System.getenv(envName(k)) != null) continue;
      Matcher m = VAR.matcher(props.getProperty(k));
      while (m.find()) if (isBlank(System.getenv(m.group(1)))) missing.add(m.group(1) + " (" + k + ")");
    }
    out.add(missing.isEmpty()
        ? new Check("environment variables", Level.OK, "every ${VAR} in the file is set", null)
        : new Check("environment variables", Level.FAIL, "not set: " + String.join(", ", missing),
            "export them in this shell (or the shell that starts the approval UI) before running"));

    java.util.function.Function<String, Optional<String>> lookup = k -> lookup(props, k);
    NetworkSettings net;
    try {
      net = NetworkSettings.from(env, lookup, props.stringPropertyNames());
    } catch (RuntimeException e) {
      out.add(new Check("network settings", Level.FAIL, e.getMessage(), null));
      return new Report(env, out);
    }
    out.add(new Check("proxy", Level.OK, net.proxy() == null ? "none (direct connection)"
        : net.proxy().scheme() + "://" + net.proxy().host() + ":" + net.proxy().port()
          + (net.proxy().user() != null ? " (with user)" : "") + ", bypass: " + net.proxy().nonProxyHosts(), null));
    if (net.relaxed()) {
      out.add(new Check("TLS", Level.WARN, "tls.relaxed=true: every certificate is trusted",
          "Use only on a dev sandbox; export the company root CA into tls.trustStore instead"));
    }
    out.add(storeCheck("trust store (company CA)", net.trustStore()));
    out.add(storeCheck("client certificate (mTLS)", net.keyStore()));
    if (!net.headers().isEmpty()) {
      out.add(new Check("default headers", Level.OK, String.join(", ", net.headers().keySet()), null));
    }

    // endpoints
    Map<String, String> targets = new LinkedHashMap<>();
    for (String k : new TreeSet<>(props.stringPropertyNames())) {
      if (k.equals("baseUri") || k.startsWith("baseUri.") || k.matches("auth\\.[^.]+\\.tokenUrl")) {
        lookup.apply(k).ifPresent(v -> targets.put(k, v));
      }
    }
    if (targets.isEmpty()) {
      out.add(new Check("baseUri", Level.FAIL, "not set", "Set baseUri=https://... in " + file));
    }
    HttpClient client;
    try {
      client = client(net);
    } catch (Exception e) {
      out.add(new Check("TLS setup", Level.FAIL, e.getMessage(), "Check the tls.* paths and passwords"));
      return new Report(env, out);
    }
    targets.forEach((key, uri) -> out.add(reach(client, net, key, uri, lookup.apply("health.path").orElse(""))));
    return new Report(env, out);
  }

  private Check reach(HttpClient client, NetworkSettings net, String key, String uri, String healthPath) {
    if (uri.contains("example.com")) {
      return new Check(key, Level.FAIL, uri + " is the template placeholder", "Set the real URL of your API");
    }
    URI target;
    try {
      target = URI.create(key.equals("baseUri") && !healthPath.isBlank() ? stripSlash(uri) + healthPath : uri);
      if (target.getHost() == null) throw new IllegalArgumentException("no host");
    } catch (IllegalArgumentException e) {
      return new Check(key, Level.FAIL, "not a URL: " + uri, null);
    }
    boolean viaProxy = net.useProxyFor(uri);
    if (!viaProxy) {
      try {
        InetAddress.getByName(target.getHost());
      } catch (UnknownHostException e) {
        return new Check(key, Level.FAIL, "DNS: cannot resolve " + target.getHost(),
            "Connect the company VPN, or the host is only reachable through the proxy (set http.proxy.*)");
      }
    }
    long start = System.nanoTime();
    try {
      HttpRequest req = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).GET().build();
      HttpResponse<Void> r = client.send(req, HttpResponse.BodyHandlers.discarding());
      long ms = (System.nanoTime() - start) / 1_000_000;
      String via = viaProxy ? " via proxy" : "";
      int s = r.statusCode();
      if (s == 407) return new Check(key, Level.FAIL, "HTTP 407 from the proxy" + via, "Set http.proxy.user / http.proxy.password");
      if (s >= 500) return new Check(key, Level.WARN, "HTTP " + s + " in " + ms + " ms" + via, "Reachable, but the service answers with a server error");
      return new Check(key, Level.OK, "HTTP " + s + " in " + ms + " ms" + via + " (any answer = reachable; 401/404 are fine here)", null);
    } catch (SSLHandshakeException e) {
      String m = String.valueOf(e.getMessage());
      boolean pkix = m.contains("PKIX") || m.contains("unable to find valid certification path");
      return new Check(key, Level.FAIL, "TLS: " + abbreviate(m),
          pkix ? "The company CA is not trusted by Java. Export it and set tls.trustStore (README: Company environment)"
               : m.contains("No subject alternative") ? "Certificate does not name this host: ask for a proper cert (tls.allowAllHostnames=true as a stopgap)"
               : "Check the client certificate (tls.keyStore) if the API requires mTLS");
    } catch (HttpConnectTimeoutException e) {
      return new Check(key, Level.FAIL, "connect timeout" + (viaProxy ? " to the proxy" : ""), firewallHint(viaProxy));
    } catch (HttpTimeoutException e) {
      return new Check(key, Level.FAIL, "no answer within 15 s", "The host accepts connections but does not answer: check the URL/port");
    } catch (ConnectException e) {
      return new Check(key, Level.FAIL, "connection refused or blocked" + (viaProxy ? " (proxy)" : ""), firewallHint(viaProxy));
    } catch (IOException e) {
      String m = String.valueOf(e.getMessage());
      if (m.contains("407")) return new Check(key, Level.FAIL, "proxy authentication required", "Set http.proxy.user / http.proxy.password");
      return new Check(key, Level.FAIL, e.getClass().getSimpleName() + ": " + abbreviate(m), firewallHint(viaProxy));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Check(key, Level.FAIL, "interrupted", null);
    }
  }

  private static HttpClient client(NetworkSettings net) throws Exception {
    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).sslContext(net.sslContext());
    if (net.proxy() != null) {
      NetworkSettings.Proxy p = net.proxy();
      ProxySelector direct = ProxySelector.of(null);
      ProxySelector viaProxy = ProxySelector.of(new java.net.InetSocketAddress(p.host(), p.port()));
      b.proxy(new ProxySelector() {
        @Override public List<java.net.Proxy> select(URI uri) {
          return (p.bypass(uri.getHost()) ? direct : viaProxy).select(uri);
        }
        @Override public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {}
      });
      if (p.user() != null) {
        // the JDK disables Basic auth for HTTPS tunnels unless told otherwise
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        b.authenticator(new Authenticator() {
          @Override protected PasswordAuthentication getPasswordAuthentication() {
            return getRequestorType() == RequestorType.PROXY
                ? new PasswordAuthentication(p.user(), (p.password() == null ? "" : p.password()).toCharArray()) : null;
          }
        });
      }
    }
    return b.build();
  }

  private static Check storeCheck(String name, NetworkSettings.Store store) {
    if (store == null) return new Check(name, Level.OK, "not configured (JVM default)", null);
    try {
      int n = java.util.Collections.list(store.load().aliases()).size();
      return new Check(name, Level.OK, store.path().getFileName() + " (" + store.type() + ", " + n + " entries)", null);
    } catch (Exception e) {
      return new Check(name, Level.FAIL, abbreviate(String.valueOf(e.getMessage())),
          "Check the path, the store type (PKCS12/JKS) and the password variable");
    }
  }

  private static Check java() {
    int v = Runtime.version().feature();
    return v >= 17 ? new Check("Java", Level.OK, System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")", null)
        : new Check("Java", Level.FAIL, System.getProperty("java.version"), "Java 17 or newer is required");
  }

  private static Check maven() {
    String mvn = System.getenv("PLATFORM_MVN");
    if (isBlank(mvn)) mvn = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    try {
      Process p = new ProcessBuilder(mvn, "-v").redirectErrorStream(true).start();
      String text = new String(p.getInputStream().readAllBytes());
      if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy();
      String first = text.lines().filter(l -> l.contains("Maven")).findFirst()
          .orElse(text.lines().filter(l -> !l.startsWith("Picked up")).findFirst().orElse(""));
      if (p.exitValue() != 0 || !first.contains("Maven")) {
        return new Check("Maven", Level.WARN, "'" + mvn + " -v' failed: " + abbreviate(first), "Check PLATFORM_MVN / PATH");
      }
      return new Check("Maven", Level.OK, first, null);
    } catch (Exception e) {
      return new Check("Maven", Level.WARN, "'" + mvn + "' not found",
          "Install Maven 3.9+ or set PLATFORM_MVN=/path/to/mvn (needed for mvn test and the Execute button)");
    }
  }

  private static String firewallHint(boolean viaProxy) {
    return viaProxy ? "Check http.proxy.host/port (ask IT for the proxy used by browsers / PAC file)"
        : "Blocked by a firewall or needs the corporate proxy: set http.proxy.* or connect the VPN";
  }

  /** Same precedence as TestConfig: -Dkey > KEY_NAME env var > file, with ${VAR} expanded. */
  static Optional<String> lookup(Properties props, String key) {
    String v = System.getProperty(key);
    if (v == null) v = System.getenv(envName(key));
    if (v == null) v = props.getProperty(key);
    if (v == null) return Optional.empty();
    Matcher m = VAR.matcher(v);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String e = System.getenv(m.group(1));
      m.appendReplacement(sb, Matcher.quoteReplacement(e == null ? "" : e));
    }
    m.appendTail(sb);
    return Optional.of(sb.toString()).filter(s -> !s.isBlank());
  }

  private static String envName(String key) { return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'); }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }

  private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

  private static String abbreviate(String s) { return s.length() > 200 ? s.substring(0, 200) + "..." : s; }

  public static String format(Report r) {
    StringBuilder sb = new StringBuilder("Environment check: " + r.env() + "\n");
    for (Check c : r.checks()) {
      sb.append(String.format("  %-4s %-28s %s%n", c.level(), c.name(), c.detail()));
      if (c.hint() != null && c.level() != Level.OK) sb.append("       -> ").append(c.hint()).append('\n');
    }
    sb.append(r.ok() ? "Ready to run tests on " + r.env() : "Fix the FAIL lines, then run doctor again").append('\n');
    return sb.toString();
  }
}
