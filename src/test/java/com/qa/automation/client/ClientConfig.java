package com.qa.automation.client;

import com.qa.automation.config.TestConfig;
import com.qa.platform.net.NetworkSettings;
import io.restassured.config.EncoderConfig;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.specification.ProxySpecification;
import io.restassured.specification.RequestSpecification;
import java.util.Map;

/**
 * Connection settings read once from config/env/&lt;env&gt;.properties: timeouts, logging and the company-network
 * settings (proxy, company CA trust store, client certificate, default headers; see {@link NetworkSettings}).
 */
public record ClientConfig(int connectTimeoutMs, int readTimeoutMs, boolean logExchanges, NetworkSettings network) {

  /** Without network settings (unit tests, tools): direct connection, JVM trust store. */
  public ClientConfig(int connectTimeoutMs, int readTimeoutMs, boolean logExchanges) {
    this(connectTimeoutMs, readTimeoutMs, logExchanges, null);
  }

  public static ClientConfig fromTestConfig() {
    TestConfig c = TestConfig.get();
    return new ClientConfig(
        c.getInt("http.connectTimeoutMs", 10_000),
        c.getInt("http.readTimeoutMs", 30_000),
        Boolean.parseBoolean(c.get("http.log", "false")),
        NetworkSettings.from(c.env(), c::opt, c.keys()));
  }

  /** baseUri.<service> if set, else baseUri. */
  public String baseUri(String service) {
    TestConfig c = TestConfig.get();
    return c.opt("baseUri." + service).orElseGet(() -> c.get("baseUri"));
  }

  /** Rest Assured config: timeouts, exact content type, TLS (company CA, client cert). */
  public RestAssuredConfig restAssured() {
    RestAssuredConfig rac = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.connection.timeout", connectTimeoutMs)
            .setParam("http.socket.timeout", readTimeoutMs))
        // send "application/json" exactly, not "application/json; charset=ISO-8859-1"
        .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false));
    if (network == null) return rac;
    SSLConfig ssl = SSLConfig.sslConfig();
    try {
      if (network.relaxed()) ssl = ssl.relaxedHTTPSValidation();
      else if (network.trustStore() != null) ssl = ssl.trustStore(network.trustStore().load());
      if (network.keyStore() != null) {
        ssl = ssl.keyStore(network.keyStore().path().toString(), network.keyStore().password())
            .keystoreType(network.keyStore().type());
      }
    } catch (Exception e) {
      // message read by the RCA EnvironmentRule ("TLS"/"certificate")
      throw new IllegalStateException("TLS certificate store could not be loaded: " + e.getMessage()
          + ". Check tls.* in config/env/" + network.env() + ".properties", e);
    }
    if (network.allowAllHostnames()) ssl = ssl.allowAllHostnames();
    return rac.sslConfig(ssl);
  }

  /** Proxy only (token endpoints): no default headers. */
  public void applyProxy(RequestSpecification spec, String uri) { applyNetwork(spec, uri, null); }

  /**
   * Proxy (unless the host is in nonProxyHosts) and the default http.header.* headers, except those named in
   * {@code skip} (the test case's own headers win). {@code skip == null}: no default headers at all.
   */
  public void applyNetwork(RequestSpecification spec, String uri, java.util.Set<String> skip) {
    if (network == null) return;
    if (network.useProxyFor(uri)) {
      NetworkSettings.Proxy p = network.proxy();
      ProxySpecification ps = ProxySpecification.host(p.host()).withPort(p.port()).withScheme(p.scheme());
      if (p.user() != null) ps = ps.withAuth(p.user(), p.password() == null ? "" : p.password());
      spec.proxy(ps);
    }
    if (skip == null) return;
    for (Map.Entry<String, String> h : network.resolvedHeaders().entrySet()) {
      boolean overridden = skip.stream().anyMatch(n -> n.equalsIgnoreCase(h.getKey()));
      if (!overridden) spec.header(h.getKey(), h.getValue());
    }
  }
}
