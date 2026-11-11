package com.qa.automation.assertions;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.automation.client.ApiResponse;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.rg.reporter.ReportContext;

/**
 * Fluent checks on an {@link ApiResponse}:
 * <pre>{@code assertThat(response).hasStatus(200).matchesSchema(schema).hasField("$.name", "doggie").respondedWithin(3000);}</pre>
 * Failure messages keep the shapes the RCA rules recognise; do not reword them without updating
 * com.qa.platform.rca.rules.
 */
public final class ResponseAssert {

  private final ApiResponse actual;

  private ResponseAssert(ApiResponse actual) { this.actual = Objects.requireNonNull(actual, "response"); }

  public static ResponseAssert assertThat(ApiResponse response) { return new ResponseAssert(response); }

  public ResponseAssert hasStatus(int expected) {
    return expect("status is " + expected, () -> {
      if (actual.status() != expected) {
        fail("Expected status " + expected + " but was " + actual.status() + " for " + actual.request().describe()
            + ". Body: " + abbreviate(actual.body(), 500));
      }
    });
  }

  public ResponseAssert isSuccessful() {
    return expect("status is 2xx", () -> {
      if (!actual.isSuccess()) fail("Expected status 2xx but was " + actual.status() + " for " + actual.request().describe());
    });
  }

  public ResponseAssert matchesSchema(JsonNode schema) {
    if (schema == null) return this;
    return expect("body matches the documented schema", () -> {
      JsonNode body;
      try {
        body = actual.json();
      } catch (RuntimeException e) {
        fail("Schema validation failed: response is not JSON: " + abbreviate(actual.body(), 300));
        return;
      }
      List<String> errors = SchemaValidator.validate(schema, body);
      if (!errors.isEmpty()) {
        fail("Schema validation failed (" + errors.size() + "): " + errors.stream().limit(10).toList());
      }
    });
  }

  /** op = equals | notNull | contains | matches | size (same ops as the test case JSON). */
  public ResponseAssert hasField(String jsonPath, String op, Object expected) {
    return expect(jsonPath + " " + op + ("notNull".equals(op) ? "" : " " + expected), () -> {
      Object value = actual.path(jsonPath);
      boolean ok = switch (op) {
        case "equals" -> String.valueOf(expected).equals(String.valueOf(value));
        case "notNull" -> value != null;
        case "contains" -> value != null && String.valueOf(value).contains(String.valueOf(expected));
        case "matches" -> value != null && String.valueOf(value).matches(String.valueOf(expected));
        case "size" -> value instanceof Collection<?> c && c.size() == Integer.parseInt(String.valueOf(expected));
        default -> throw new IllegalArgumentException("Unknown assertion op: " + op);
      };
      if (!ok) fail("JSON path " + jsonPath + " " + op + " expected [" + expected + "] but was [" + value + "]");
    });
  }

  public ResponseAssert hasField(String jsonPath, Object expected) { return hasField(jsonPath, "equals", expected); }

  public ResponseAssert hasHeader(String name, String expected) {
    return expect("header " + name + " is " + expected, () -> {
      String value = actual.header(name).orElse(null);
      if (!Objects.equals(expected, value)) fail("Header " + name + " expected [" + expected + "] but was [" + value + "]");
    });
  }

  public ResponseAssert respondedWithin(long maxMs) {
    return expect("responded within " + maxMs + " ms", () -> {
      if (actual.timeMs() > maxMs) fail("Response time " + actual.timeMs() + " ms exceeded " + maxMs + " ms");
    });
  }

  /** Each check is an "expect" step in the report's Steps tab; a no-op wrapper when the report plugin is off. */
  private ResponseAssert expect(String title, Runnable check) {
    ReportContext.expect(title, check::run);
    return this;
  }

  private static void fail(String message) { throw new AssertionError(message); }

  private static String abbreviate(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "..."; }
}
