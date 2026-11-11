package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.qa.platform.net.NetworkSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

public class NetworkSettingsTest {

  private static NetworkSettings of(String env, Map<String, String> m) {
    return NetworkSettings.from(env, k -> Optional.ofNullable(m.get(k)), m.keySet());
  }

  @Test
  public void nothingConfiguredMeansDirectConnection() {
    NetworkSettings n = of("qa", Map.of("baseUri", "https://api.company.com"));
    assertNull(n.proxy());
    assertNull(n.trustStore());
    assertFalse(n.useProxyFor("https://api.company.com"));
    assertTrue(n.headers().isEmpty());
  }

  @Test
  public void proxyIsBypassedForNonProxyHosts() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("http.proxy.host", "proxy.company.com");
    m.put("http.proxy.port", "3128");
    m.put("http.proxy.nonProxyHosts", "localhost|*.company.internal");
    NetworkSettings n = of("sit", m);
    assertEquals(n.proxy().port(), 3128);
    assertTrue(n.useProxyFor("https://partner.example.org/v1"));
    assertFalse(n.useProxyFor("https://orders.sit.company.internal/api"));
    assertFalse(n.useProxyFor("http://localhost:8080"));
  }

  @Test
  public void defaultHeadersGetANewUuidPerRequest() {
    NetworkSettings n = of("qa", Map.of("http.header.X-Correlation-Id", "${uuid}", "http.header.X-Consumer", "api-tests"));
    String a = n.resolvedHeaders().get("X-Correlation-Id");
    String b = n.resolvedHeaders().get("X-Correlation-Id");
    assertTrue(a.matches("[0-9a-f-]{36}"));
    assertFalse(a.equals(b));
    assertEquals(n.resolvedHeaders().get("X-Consumer"), "api-tests");
  }

  @Test
  public void relaxedTlsIsRefusedForProduction() {
    assertThrows(IllegalStateException.class, () -> of("prod", Map.of("tls.relaxed", "true")));
    assertThrows(IllegalStateException.class, () -> of("eu-prd", Map.of("tls.relaxed", "true")));
    assertTrue(of("dev-sandbox", Map.of("tls.relaxed", "true")).relaxed());
  }

  @Test
  public void storeTypeFollowsTheFileExtension() {
    assertEquals(of("qa", Map.of("tls.trustStore", "config/certs/ca.jks")).trustStore().type(), "JKS");
    assertEquals(of("qa", Map.of("tls.trustStore", "config/certs/ca.p12")).trustStore().type(), "PKCS12");
  }
}
