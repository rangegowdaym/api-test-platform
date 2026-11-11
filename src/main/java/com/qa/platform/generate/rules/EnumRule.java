package com.qa.platform.generate.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.common.Json;
import com.qa.platform.generate.CaseFactory;
import com.qa.platform.generate.CaseRule;
import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.Param;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every allowed enum value (body fields and query params) plus one invalid value. */
public class EnumRule implements CaseRule {
  @Override public String category() { return "ENUM"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    List<TestCase> out = new ArrayList<>();
    CaseRequest valid = f.validRequest(e);
    int ok = Integer.parseInt(e.successCode());

    JsonNode schema = e.requestBodySchema();
    if (schema != null && valid.body() instanceof ObjectNode body) {
      Iterator<Map.Entry<String, JsonNode>> props = schema.path("properties").fields();
      while (props.hasNext()) {
        Map.Entry<String, JsonNode> p = props.next();
        if (!p.getValue().has("enum")) continue;
        for (JsonNode v : p.getValue().get("enum")) {
          ObjectNode b = body.deepCopy();
          b.set(p.getKey(), v);
          out.add(f.build(e, category(), p.getKey() + "_" + v.asText(), "P3",
              e.method() + " " + e.path() + " with " + p.getKey() + "=" + v.asText() + " returns " + ok,
              CaseFactory.withBody(valid, b), ok, true, null));
        }
        ObjectNode b = body.deepCopy();
        b.set(p.getKey(), JsonNodeFactory.instance.textNode("INVALID_ENUM_VALUE"));
        List<String> notes = new ArrayList<>();
        int code = f.clientError(e, notes, "400", "422");
        out.add(f.build(e, category(), p.getKey() + "_invalid", "P2",
            e.method() + " " + e.path() + " with invalid " + p.getKey() + " returns " + code,
            CaseFactory.withBody(valid, b), code, true, notes));
      }
    }

    for (Param p : e.params("query")) {
      JsonNode s = p.schema();
      JsonNode values = s == null ? null : (s.has("enum") ? s.get("enum") : s.path("items").get("enum"));
      if (values == null) continue;
      for (JsonNode v : values) {
        Map<String, Object> q = new LinkedHashMap<>(valid.queryParams());
        q.put(p.name(), Json.MAPPER.convertValue(v, Object.class));
        out.add(f.build(e, category(), "query_" + p.name() + "_" + v.asText(), "P3",
            e.method() + " " + e.path() + "?" + p.name() + "=" + v.asText() + " returns " + ok,
            CaseFactory.withQuery(valid, q), ok, true, null));
      }
    }
    return out;
  }
}
