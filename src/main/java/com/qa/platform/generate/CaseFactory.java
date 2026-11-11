package com.qa.platform.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.common.Hashing;
import com.qa.platform.common.Json;
import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.Expected;
import com.qa.platform.model.Param;
import com.qa.platform.model.ResponseSpec;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds TestCase objects for a rule: a valid baseline request, then the rule mutates one thing. */
public class CaseFactory {

  private final ValueGenerator values;
  private final String specName;
  private final String specHash;

  public CaseFactory(ValueGenerator values, String specName, String specHash) {
    this.values = values;
    this.specName = specName;
    this.specHash = specHash;
  }

  public ValueGenerator values() { return values; }

  /** The request that should succeed. Rules copy and break it. */
  public CaseRequest validRequest(Endpoint e) {
    return new CaseRequest(
        params(e, "path"), params(e, "query"), headerParams(e),
        e.secured() ? "default" : "none",
        e.requestBodySchema() == null ? null : values.valid(e.requestBodySchema()));
  }

  private Map<String, Object> params(Endpoint e, String in) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Param p : e.params(in)) {
      if ("path".equals(in)) {
        // real ids come from config/env/<env>.properties (testdata.<name>) or from a value saved by an earlier step
        m.put(p.name(), "${testdata." + p.name() + "}");
        continue;
      }
      if (!p.required()) continue;
      m.put(p.name(), p.example() != null ? p.example() : Json.MAPPER.convertValue(values.valid(p.schema()), Object.class));
    }
    return m;
  }

  private Map<String, String> headerParams(Endpoint e) {
    Map<String, String> m = new LinkedHashMap<>();
    if (e.requestBodySchema() != null) m.put("Content-Type", "application/json");
    m.put("Accept", "application/json");
    for (Param p : e.params("header")) if (p.required()) m.put(p.name(), values.valid(p.schema()).asText());
    return m;
  }

  public TestCase build(Endpoint e, String category, String detail, String priority, String title,
                        CaseRequest request, int expectedStatus, boolean withSchema, List<String> notes) {
    ResponseSpec rs = e.responses().get(String.valueOf(expectedStatus));
    JsonNode schema = withSchema && rs != null ? rs.schema() : null;
    List<Expected.Assertion> assertions = new ArrayList<>();
    if (expectedStatus / 100 == 2 && request.body() instanceof ObjectNode body && schema != null
        && schema.path("properties").has("name") && body.has("name")) {
      assertions.add(new Expected.Assertion("$.name", "equals", Json.MAPPER.convertValue(body.get("name"), Object.class)));
    }
    Expected expected = new Expected(expectedStatus, schema, assertions.isEmpty() ? null : assertions, 3000L);

    List<String> tags = new ArrayList<>(e.tags().stream().map(t -> t.trim().replaceAll("[^A-Za-z0-9_.-]+", "-")).toList());
    tags.add(priority);
    if ("POSITIVE".equals(category)) tags.add("smoke");
    tags.add("regression");

    TestCase tc = new TestCase(
        CaseIds.of(e, category, detail), title, category, priority, tags, TestCase.PENDING, 1, "NEW", null,
        new TestCase.Source(specName, specHash, e.path(), e.method(), e.operationId()),
        request, expected, null, notes == null || notes.isEmpty() ? null : notes);
    return tc.withHash(contentHash(tc));
  }

  /** Hash of what is tested (request + expected), not of status/review, so only real changes reset approval. */
  public static String contentHash(TestCase tc) {
    try {
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("request", tc.request());
      content.put("expected", tc.expected());
      return Hashing.sha256(Json.MAPPER.writeValueAsString(content)).substring(0, 16);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Expected 4xx code: the one the spec documents, else 400 with a note for the reviewer. */
  public int clientError(Endpoint e, List<String> notes, String... preferred) {
    return e.documented(preferred).map(Integer::parseInt).orElseGet(() -> {
      notes.add("Expected status assumed (" + preferred[0] + "): the spec documents none. Check before approving.");
      return Integer.parseInt(preferred[0]);
    });
  }

  public static CaseRequest withBody(CaseRequest r, JsonNode body) {
    return new CaseRequest(r.pathParams(), r.queryParams(), r.headers(), r.auth(), body);
  }

  public static CaseRequest withAuth(CaseRequest r, String auth) {
    return new CaseRequest(r.pathParams(), r.queryParams(), r.headers(), auth, r.body());
  }

  public static CaseRequest withQuery(CaseRequest r, Map<String, Object> q) {
    return new CaseRequest(r.pathParams(), q, r.headers(), r.auth(), r.body());
  }

  public static CaseRequest withPath(CaseRequest r, Map<String, Object> p) {
    return new CaseRequest(p, r.queryParams(), r.headers(), r.auth(), r.body());
  }
}
