package com.qa.automation.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import io.restassured.response.Response;
import java.util.Optional;

/**
 * Read-only view of a response. Callers never touch Rest Assured types; {@link #raw()} is there only as an
 * escape hatch inside the client package's tests.
 */
public final class ApiResponse {

  private final ApiRequest request;
  private final Response raw;
  private final long timeMs;
  private JsonNode json;               // parsed lazily, once

  ApiResponse(ApiRequest request, Response raw, long timeMs) {
    this.request = request;
    this.raw = raw;
    this.timeMs = timeMs;
  }

  public ApiRequest request() { return request; }
  public int status() { return raw.statusCode(); }
  public boolean isSuccess() { return status() / 100 == 2; }
  public long timeMs() { return timeMs; }
  public String body() { return raw.asString(); }
  public Optional<String> header(String name) { return Optional.ofNullable(raw.getHeader(name)); }
  public String contentType() { return raw.getContentType(); }

  /** Body as a Jackson tree; throws {@link ApiClientException} if the body is not JSON. */
  public JsonNode json() {
    if (json == null) {
      try {
        json = Json.MAPPER.readTree(body());
      } catch (Exception e) {
        throw new ApiClientException("Response of " + request.describe() + " is not JSON: " + abbreviate(body()), e);
      }
    }
    return json;
  }

  /** Value at a JSONPath-like path ("$.a.b[0]" or "a.b[0]"), or null when absent. Uses Rest Assured's GPath. */
  public <T> T path(String jsonPath) {
    String gpath = jsonPath == null ? "" : jsonPath.replaceFirst("^\\$\\.?", "");
    return gpath.isEmpty() ? raw.jsonPath().get() : raw.jsonPath().get(gpath);
  }

  public <T> T as(Class<T> type) {
    try {
      return Json.MAPPER.readValue(body(), type);
    } catch (Exception e) {
      throw new ApiClientException("Cannot map response of " + request.describe() + " to " + type.getSimpleName(), e);
    }
  }

  public <T> T as(TypeReference<T> type) {
    try {
      return Json.MAPPER.readValue(body(), type);
    } catch (Exception e) {
      throw new ApiClientException("Cannot map response of " + request.describe() + " to " + type.getType(), e);
    }
  }

  Response raw() { return raw; }

  static String abbreviate(String s) { return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "..."; }

  @Override public String toString() { return request.describe() + " -> " + status() + " in " + timeMs + " ms"; }
}
