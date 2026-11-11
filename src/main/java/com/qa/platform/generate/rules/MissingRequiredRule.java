package com.qa.platform.generate.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.generate.CaseFactory;
import com.qa.platform.generate.CaseRule;
import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.Param;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Drops each required body field and each required query parameter, one at a time. */
public class MissingRequiredRule implements CaseRule {
  @Override public String category() { return "MISSING_REQUIRED"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    List<TestCase> out = new ArrayList<>();
    CaseRequest valid = f.validRequest(e);

    JsonNode schema = e.requestBodySchema();
    if (schema != null && valid.body() instanceof ObjectNode body) {
      for (JsonNode req : schema.path("required")) {
        String field = req.asText();
        ObjectNode broken = body.deepCopy();
        broken.remove(field);
        List<String> notes = new ArrayList<>();
        int code = f.clientError(e, notes, "400", "422");
        out.add(f.build(e, category(), "body_" + field, "P2",
            e.method() + " " + e.path() + " without required field '" + field + "' returns " + code,
            CaseFactory.withBody(valid, broken), code, true, notes));
      }
      if (e.bodyRequired()) {
        List<String> notes = new ArrayList<>();
        int code = f.clientError(e, notes, "400", "415", "422");
        out.add(f.build(e, category(), "body_empty", "P2",
            e.method() + " " + e.path() + " with no body returns " + code,
            CaseFactory.withBody(valid, null), code, false, notes));
      }
    }

    for (Param p : e.params("query")) {
      if (!p.required()) continue;
      Map<String, Object> q = new LinkedHashMap<>(valid.queryParams());
      q.remove(p.name());
      List<String> notes = new ArrayList<>();
      int code = f.clientError(e, notes, "400", "422");
      out.add(f.build(e, category(), "query_" + p.name(), "P2",
          e.method() + " " + e.path() + " without required query '" + p.name() + "' returns " + code,
          CaseFactory.withQuery(valid, q), code, true, notes));
    }
    return out;
  }
}
