package com.qa.platform.net;

import com.qa.platform.common.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Company-network settings of one environment (config/env/&lt;env&gt;.properties), shared by the test client
 * (Rest Assured, via {@code com.qa.automation.client.ClientConfig}) and the {@code doctor} command (JDK HttpClient).
 *
 * <pre>
 * http.proxy.host / .port / .scheme / .user / .password / .nonProxyHosts   corporate proxy
 * tls.trustStore / .trustStorePassword / .trustStoreType                  company root CA (PKCS12 or JKS)
 * tls.keyStore / .keyStorePassword / .keyStoreType                        client certificate (mTLS)
 * tls.allowAllHostnames                                                   certificate without the host name (avoid)
 * tls.relaxed                                                             trust any certificate: dev sandboxes only
 * http.header.&lt;Name&gt;=value                                                 sent on every request; ${uuid} = new UUID per request
 * </pre>
 * Without these keys nothing changes: no proxy, the JVM's default trust store.
 */
public record NetworkSettings(
    String env,
    Proxy proxy,
    Store trustStore,
    Store keyStore,
    boolean allowAllHostnames,
    boolean relaxed,
    Map<String, String> headers) {

  public record Proxy(String host, int port, String scheme, String user, String password, String nonProxyHosts) {
    /** Java's nonProxyHosts syntax: host|*.corp.example.com|10.* */
    public boolean bypass(String targetHost) {
      if (nonProxyHosts == null || nonProxyHosts.isBlank() || targetHost == null) return false;
      for (String p : nonProxyHosts.split("[|,]")) {
        String glob = p.trim().toLowerCase(Locale.ROOT);
        if (glob.isEmpty()) continue;
        String regex = Pattern.quote(glob).replace("*", "\\E.*\\Q");
        if (targetHost.toLowerCase(Locale.ROOT).matches(regex)) return true;
      }
      return false;
    }

    public InetSocketAddress address() { return InetSocketAddress.createUnresolved(host, port); }
  }

  public record Store(Path path, String password, String type) {
    public KeyStore load() throws IOException, GeneralSecurityException {
      if (!Files.exists(path)) throw new IOException("Key/trust store not found: " + path.toAbsolutePath());
      KeyStore ks = KeyStore.getInstance(type);
      try (InputStream in = Files.newInputStream(path)) {
        ks.load(in, password == null ? null : password.toCharArray());
      }
      return ks;
    }
  }

  private static final Pattern PROD = Pattern.compile("(?i).*\\bprod.*|.*prd.*|.*live.*");

  /**
   * @param lookup returns the expanded value of a key (env vars resolved), or empty
   * @param keys   every key of the env file (for the http.header.* entries)
   */
  public static NetworkSettings from(String env, Function<String, Optional<String>> lookup, Iterable<String> keys) {
    Proxy proxy = lookup.apply("http.proxy.host")
        .map(h -> new Proxy(h,
            Integer.parseInt(lookup.apply("http.proxy.port").orElse("8080")),
            lookup.apply("http.proxy.scheme").orElse("http"),
            lookup.apply("http.proxy.user").orElse(null),
            lookup.apply("http.proxy.password").orElse(null),
            lookup.apply("http.proxy.nonProxyHosts").orElse("localhost|127.0.0.1")))
        .orElse(null);
    Store trust = store(lookup, "tls.trustStore");
    Store key = store(lookup, "tls.keyStore");
    boolean relaxed = Boolean.parseBoolean(lookup.apply("tls.relaxed").orElse("false"));
    if (relaxed && PROD.matcher(env).matches()) {
      throw new IllegalStateException("tls.relaxed=true is refused for environment '" + env
          + "': add the company CA with tls.trustStore instead");
    }
    return new NetworkSettings(env, proxy, trust, key,
        Boolean.parseBoolean(lookup.apply("tls.allowAllHostnames").orElse("false")), relaxed, headers(keys, lookup));
  }

  /** Collects http.header.Name=value pairs out of a key set. */
  static Map<String, String> headers(Iterable<String> keys, Function<String, Optional<String>> lookup) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String k : keys) {
      if (k.startsWith("http.header.") && k.length() > "http.header.".length()) {
        lookup.apply(k).ifPresent(v -> out.put(k.substring("http.header.".length()), v));
      }
    }
    return out;
  }

  /** Header values with ${uuid} replaced: call once per request. */
  public Map<String, String> resolvedHeaders() {
    if (headers.isEmpty()) return headers;
    Map<String, String> out = new LinkedHashMap<>();
    headers.forEach((k, v) -> out.put(k, v.replace("${uuid}", UUID.randomUUID().toString())));
    return out;
  }

  public boolean useProxyFor(String uri) {
    if (proxy == null) return false;
    try {
      return !proxy.bypass(URI.create(uri).getHost());
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /** SSLContext with the configured trust/key stores (JVM defaults for what is not configured). */
  public SSLContext sslContext() throws IOException, GeneralSecurityException {
    TrustManager[] tms = null;
    if (relaxed) {
      tms = new TrustManager[] {new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }};
    } else if (trustStore != null) {
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore.load());
      tms = tmf.getTrustManagers();
    }
    javax.net.ssl.KeyManager[] kms = null;
    if (keyStore != null) {
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore.load(), keyStore.password() == null ? null : keyStore.password().toCharArray());
      kms = kmf.getKeyManagers();
    }
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kms, tms, new SecureRandom());
    return ctx;
  }

  private static Store store(Function<String, Optional<String>> lookup, String prefix) {
    return lookup.apply(prefix).map(p -> {
      Path path = Path.of(p);
      if (!path.isAbsolute()) path = Workspace.ROOT.resolve(p);
      String type = lookup.apply(prefix + "Type").orElse(p.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12");
      return new Store(path, lookup.apply(prefix + "Password").orElse(null), type);
    }).orElse(null);
  }
}
