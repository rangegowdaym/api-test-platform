package com.qa.automation.client;

/** HTTP methods supported by {@link ApiClient}; decouples callers from Rest Assured's own enum. */
public enum HttpMethod {
  GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS;

  public static HttpMethod of(String method) {
    return valueOf(method.trim().toUpperCase(java.util.Locale.ROOT));
  }

  io.restassured.http.Method toRestAssured() {
    return io.restassured.http.Method.valueOf(name());
  }
}
