package com.qa.platform.generate.rules;

import com.fasterxml.jackson.databind.JsonNode;
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

/** Puts a value of the wrong JSON type into each top-level body field (capped to keep review volume sane). */
public class TypeMismatchRule implements CaseRule {
  private static final int MAX_FIELDS = 5;

  @Override public String category() { return "TYPE_MISMATCH"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    List<TestCase> out = new ArrayList<>();
    CaseRequest valid = f.validRequest(e);
    JsonNode schema = e.requestBodySchema();
    if (schema == null || !(valid.body() instanceof ObjectNode body)) return out;

    Iterator<Map.Entry<String, JsonNode>> props = schema.path("properties").fields();
    int n = 0;
    while (props.hasNext() && n < MAX_FIELDS) {
      Map.Entry<String, JsonNode> p = props.next();
      if (!body.has(p.getKey())) continue;
      ObjectNode broken = body.deepCopy();
      broken.set(p.getKey(), f.values().wrongType(p.getValue()));
      List<String> notes = new ArrayList<>();
      int code = f.clientError(e, notes, "400", "422");
      out.add(f.build(e, category(), p.getKey(), "P2",
          e.method() + " " + e.path() + " with wrong type for '" + p.getKey() + "' returns " + code,
          CaseFactory.withBody(valid, broken), code, true, notes));
      n++;
    }
    return out;
  }
}
