package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;

/** API key in a header (auth.<profile>.type=apikey, .header=X-API-Key, .key=${VAR}). */
public final class ApiKeyAuth implements AuthProvider {
  private final String header;
  private final String key;
  public ApiKeyAuth(String header, String key) { this.header = header; this.key = key; }
  @Override public void apply(RequestSpecification spec) { spec.header(header, key); }
}
