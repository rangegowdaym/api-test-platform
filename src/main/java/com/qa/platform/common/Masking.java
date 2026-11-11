package com.qa.platform.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes secrets before anything is written to evidence, reports or sent to an LLM. */
public final class Masking {
  private static final Set<String> SECRET_HEADERS = Set.of(
      "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key", "x-auth-token");
  private static final Pattern SECRET_JSON = Pattern.compile(
      "(\"(?i:password|passwd|secret|token|access_token|refresh_token|client_secret|apiKey|api_key)\"\\s*:\\s*\")[^\"]*(\")");
  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

  private Masking() {}

  public static Map<String, String> headers(Map<String, String> in) {
    Map<String, String> out = new LinkedHashMap<>();
    if (in == null) return out;
    in.forEach((k, v) -> out.put(k, SECRET_HEADERS.contains(k.toLowerCase()) ? "***" : v));
    return out;
  }

  public static String text(String s) {
    if (s == null) return null;
    String masked = SECRET_JSON.matcher(s).replaceAll("$1***$2");
    return BEARER.matcher(masked).replaceAll("$1***");
  }
}
