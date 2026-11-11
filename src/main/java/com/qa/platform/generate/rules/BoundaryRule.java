package com.qa.platform.generate.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.generate.CaseFactory;
import com.qa.platform.generate.CaseRule;
import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** minLength / maxLength / minimum / maximum: one case at the limit (should pass), one just past it (should fail). */
public class BoundaryRule implements CaseRule {
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  @Override public String category() { return "BOUNDARY"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    List<TestCase> out = new ArrayList<>();
    CaseRequest valid = f.validRequest(e);
    JsonNode schema = e.requestBodySchema();
    if (schema == null || !(valid.body() instanceof ObjectNode body)) return out;
    int ok = Integer.parseInt(e.successCode());

    Iterator<Map.Entry<String, JsonNode>> props = schema.path("properties").fields();
    while (props.hasNext()) {
      Map.Entry<String, JsonNode> p = props.next();
      String field = p.getKey();
      JsonNode s = p.getValue();
      if (s.has("maxLength")) {
        int max = s.get("maxLength").asInt();
        out.add(edge(e, f, valid, body, field, "max_length", F.textNode("a".repeat(max)), ok, true));
        out.add(edge(e, f, valid, body, field, "max_length_plus_1", F.textNode("a".repeat(max + 1)), 0, false));
      }
      if (s.has("minLength") && s.get("minLength").asInt() > 0) {
        int min = s.get("minLength").asInt();
        out.add(edge(e, f, valid, body, field, "min_length", F.textNode("a".repeat(min)), ok, true));
        out.add(edge(e, f, valid, body, field, "min_length_minus_1", F.textNode("a".repeat(min - 1)), 0, false));
      }
      if (s.has("maximum")) {
        long max = s.get("maximum").asLong();
        out.add(edge(e, f, valid, body, field, "maximum", F.numberNode(max), ok, true));
        out.add(edge(e, f, valid, body, field, "maximum_plus_1", F.numberNode(max + 1), 0, false));
      }
      if (s.has("minimum")) {
        long min = s.get("minimum").asLong();
        out.add(edge(e, f, valid, body, field, "minimum", F.numberNode(min), ok, true));
        out.add(edge(e, f, valid, body, field, "minimum_minus_1", F.numberNode(min - 1), 0, false));
      }
    }
    return out;
  }

  private TestCase edge(Endpoint e, CaseFactory f, CaseRequest valid, ObjectNode body, String field, String detail,
                        JsonNode value, int okCode, boolean shouldPass) {
    ObjectNode b = body.deepCopy();
    b.set(field, value);
    List<String> notes = new ArrayList<>();
    int code = shouldPass ? okCode : f.clientError(e, notes, "400", "422");
    return f.build(e, category(), field + "_" + detail, "P3",
        e.method() + " " + e.path() + " with '" + field + "' at " + detail.replace('_', ' ') + " returns " + code,
        CaseFactory.withBody(valid, b), code, shouldPass, notes);
  }
}
