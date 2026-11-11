package com.qa.automation.support;

import com.qa.platform.model.Evidence;

/** A one-paste reproduction for developers. Uses the masked headers: replace *** with a real token. */
public final class CurlBuilder {
  private CurlBuilder() {}

  public static String of(Evidence.HttpRequest r) {
    if (r == null) return null;
    StringBuilder sb = new StringBuilder("curl -i -X ").append(r.method()).append(" '").append(r.url()).append('\'');
    if (r.headers() != null) r.headers().forEach((k, v) -> sb.append(" \\\n  -H '").append(k).append(": ").append(v).append('\''));
    if (r.body() != null && !r.body().isBlank()) sb.append(" \\\n  --data-raw '").append(r.body().replace("'", "'\\''")).append('\'');
    return sb.toString();
  }
}
