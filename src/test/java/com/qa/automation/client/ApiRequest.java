package com.qa.automation.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of one HTTP call. Build it with {@link #builder(HttpMethod, String)}:
 * <pre>{@code
 * ApiRequest req = ApiRequest.builder(HttpMethod.GET, "/orders/{orderId}")
 *     .service("orders").auth("default").pathParam("orderId", 42).build();
 * }</pre>
 * {@code service} selects the base URI (config key {@code baseUri.<service>}, falling back to {@code baseUri});
 * {@code auth} names an auth profile from config, or "none" / "invalid".
 */
public final class ApiRequest {

  private final HttpMethod method;
  private final String path;
  private final String service;
  private final String auth;
  private final Map<String, Object> pathParams;
  private final Map<String, Object> queryParams;
  private final Map<String, String> headers;
  private final Object body;
  private final String contentType;

  private ApiRequest(Builder b) {
    this.method = b.method;
    this.path = b.path;
    this.service = b.service;
    this.auth = b.auth;
    this.pathParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.pathParams));
    this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.queryParams));
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    this.body = b.body;
    this.contentType = b.contentType;
  }

  public static Builder builder(HttpMethod method, String path) { return new Builder(method, path); }

  public HttpMethod method() { return method; }
  public String path() { return path; }
  public String service() { return service; }
  public String auth() { return auth; }
  public Map<String, Object> pathParams() { return pathParams; }
  public Map<String, Object> queryParams() { return queryParams; }
  public Map<String, String> headers() { return headers; }
  public Object body() { return body; }
  public String contentType() { return contentType; }

  /** "POST /orders", used in logs, errors and RCA. */
  public String describe() { return method + " " + path; }

  /** A builder pre-filled with this request, for small variations (e.g. the same call without auth). */
  public Builder toBuilder() {
    Builder b = new Builder(method, path).service(service).auth(auth).body(body).contentType(contentType);
    b.pathParams.putAll(pathParams);
    b.queryParams.putAll(queryParams);
    b.headers.putAll(headers);
    return b;
  }

  public static final class Builder {
    private final HttpMethod method;
    private final String path;
    private String service = "default";
    private String auth = "none";
    private final Map<String, Object> pathParams = new LinkedHashMap<>();
    private final Map<String, Object> queryParams = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Object body;
    private String contentType;

    private Builder(HttpMethod method, String path) {
      this.method = Objects.requireNonNull(method, "method");
      this.path = Objects.requireNonNull(path, "path");
    }

    public Builder service(String service) { this.service = service == null ? "default" : service; return this; }
    public Builder auth(String profile) { this.auth = profile == null ? "none" : profile; return this; }
    public Builder pathParam(String name, Object value) { pathParams.put(name, value); return this; }
    public Builder pathParams(Map<String, ?> values) { if (values != null) pathParams.putAll(values); return this; }
    public Builder queryParam(String name, Object value) { queryParams.put(name, value); return this; }
    public Builder queryParams(Map<String, ?> values) { if (values != null) queryParams.putAll(values); return this; }
    public Builder header(String name, String value) { headers.put(name, value); return this; }
    public Builder headers(Map<String, String> values) { if (values != null) headers.putAll(values); return this; }
    /** Any object: String and JsonNode are sent as-is, anything else is serialised with the platform's Jackson mapper. */
    public Builder body(Object body) { this.body = body; return this; }
    public Builder contentType(String contentType) { this.contentType = contentType; return this; }

    public ApiRequest build() { return new ApiRequest(this); }
  }
}
