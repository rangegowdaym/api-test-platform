package com.qa.automation.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.automation.client.ApiClient;
import com.qa.automation.client.ApiRequest;
import com.qa.automation.client.ApiResponse;
import com.qa.automation.client.HttpMethod;
import com.qa.automation.context.ScenarioContext;
import com.qa.automation.support.Placeholders;
import com.qa.platform.common.Json;
import com.qa.platform.model.TestCase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Turns an approved JSON test case (plus the scenario's overrides and saved variables) into an {@link ApiRequest}.
 * Generated {@code <Tag>Steps} call {@link #prepare} and send it through the generated {@code <Tag>Api};
 * {@link #execute} sends it directly (legacy step "I send the ... request to ...").
 * The OpenAPI tag of the case selects the service (base URI key {@code baseUri.<tag>}).
 */
public final class OperationService {

  private final ApiClient client;

  public OperationService(ApiClient client) { this.client = client; }

  public ApiResponse execute(TestCase tc, ScenarioContext ctx) {
    ApiRequest request = toRequest(tc, ctx);
    return client.send(request);
  }

  /**
   * Used by the GENERATED {@code <Tag>Steps}: resolves the scenario's test case into the arguments of the
   * matching generated {@code <Tag>Api} method. Fails fast when the feature and the test case disagree.
   */
  public Call prepare(ScenarioContext ctx, String operationId) {
    TestCase tc = Objects.requireNonNull(ctx.testCase, "Use 'Given test case \"<id>\"' before 'When I call the ... operation'");
    if (!operationId.equals(tc.source().operationId())) {
      throw new IllegalStateException("Step calls operation '" + operationId + "' but test case " + tc.id() + " is for '"
          + tc.source().operationId() + "' (" + ctx.endpoint + "). Regenerate the features.");
    }
    return new Call(tc.id(), toRequest(tc, ctx));
  }

  /** The resolved request of one test case, split into what a generated Api method takes. */
  public static final class Call {
    private final String caseId;
    private final ApiRequest request;

    Call(String caseId, ApiRequest request) {
      this.caseId = caseId;
      this.request = request;
    }

    /** Auth profile: the case's, unless a step changed it ("I send no credentials", "I am authenticated as"). */
    public String auth() { return request.auth(); }

    public Object path(String name) {
      Object v = request.pathParams().get(name);
      if (v == null) {
        throw new IllegalStateException("Test case " + caseId + " has no value for path parameter '" + name
            + "'. Set it in the case (approval UI) or in config/env/<env>.properties as testdata." + name);
      }
      return v;
    }

    public Object body() { return request.body(); }

    public Map<String, ?> query() { return request.queryParams(); }

    /**
     * Everything the Api method signature does not carry: headers, content type, and query/body when the
     * operation declares none (negative cases may still send them).
     */
    public Consumer<ApiRequest.Builder> customize(boolean apiTakesQuery, boolean apiTakesBody) {
      return b -> {
        b.pathParams(request.pathParams());
        b.headers(request.headers());
        if (!apiTakesQuery) b.queryParams(request.queryParams());
        if (!apiTakesBody && request.body() != null) b.body(request.body());
        if (request.contentType() != null) b.contentType(request.contentType());
      };
    }

    public ApiRequest request() { return request; }
  }

  ApiRequest toRequest(TestCase tc, ScenarioContext ctx) {
    var r = tc.request();
    ApiRequest.Builder b = ApiRequest.builder(HttpMethod.of(tc.source().method()), tc.source().path())
        .service(tc.tags().get(0))
        .auth(ctx.authProfile != null ? ctx.authProfile : r.auth());

    Map<String, Object> path = resolve(r.pathParams(), ctx);
    path.putAll(ctx.pathOverrides);
    b.pathParams(path);

    Map<String, Object> query = resolve(r.queryParams(), ctx);
    query.putAll(ctx.queryOverrides);
    b.queryParams(query);

    Map<String, Object> headers = resolve(r.headers(), ctx);
    headers.putAll(ctx.headerOverrides);
    headers.forEach((k, v) -> b.header(k, String.valueOf(v)));

    if (r.body() != null) b.body(applyOverrides(resolveTree(r.body(), ctx), ctx));
    return b.build();
  }

  private static Map<String, Object> resolve(Map<String, ?> in, ScenarioContext ctx) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (in != null) in.forEach((k, v) -> out.put(k, Placeholders.resolve(v, ctx)));
    return out;
  }

  private static JsonNode resolveTree(JsonNode n, ScenarioContext ctx) {
    if (n.isTextual()) return Json.MAPPER.valueToTree(Placeholders.resolve(n.asText(), ctx));
    if (n.isObject()) {
      ObjectNode o = Json.MAPPER.createObjectNode();
      n.fields().forEachRemaining(e -> o.set(e.getKey(), resolveTree(e.getValue(), ctx)));
      return o;
    }
    if (n.isArray()) {
      ArrayNode a = Json.MAPPER.createArrayNode();
      n.forEach(x -> a.add(resolveTree(x, ctx)));
      return a;
    }
    return n;
  }

  /** "$.owner.name" -> value overrides set by the "I set body field" step. */
  private static JsonNode applyOverrides(JsonNode body, ScenarioContext ctx) {
    if (ctx.bodyOverrides.isEmpty() || !(body instanceof ObjectNode root)) return body;
    for (Map.Entry<String, Object> e : ctx.bodyOverrides.entrySet()) {
      String[] parts = e.getKey().replaceFirst("^\\$\\.", "").split("\\.");
      ObjectNode cur = root;
      for (int i = 0; i < parts.length - 1; i++) {
        JsonNode next = cur.get(parts[i]);
        cur = next instanceof ObjectNode o ? o : cur.putObject(parts[i]);
      }
      cur.set(parts[parts.length - 1], Json.MAPPER.valueToTree(e.getValue()));
    }
    return root;
  }
}
