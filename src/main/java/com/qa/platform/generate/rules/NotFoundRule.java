package com.qa.platform.generate.rules;

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

/** Path parameters that look like ids get an id that cannot exist. */
public class NotFoundRule implements CaseRule {
  @Override public String category() { return "NOT_FOUND"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    List<TestCase> out = new ArrayList<>();
    if ("POST".equals(e.method())) return out;
    CaseRequest valid = f.validRequest(e);
    for (Param p : e.params("path")) {
      String n = p.name().toLowerCase();
      if (!(n.endsWith("id") || n.endsWith("key") || n.endsWith("code") || n.endsWith("name"))) continue;
      Map<String, Object> path = new LinkedHashMap<>(valid.pathParams());
      boolean numeric = p.schema() != null && p.schema().path("type").asText("").matches("integer|number");
      path.put(p.name(), numeric ? 987654321L : "00000000-0000-0000-0000-000000000000");
      List<String> notes = new ArrayList<>();
      int code = f.clientError(e, notes, "404");
      out.add(f.build(e, category(), p.name(), "P2",
          e.method() + " " + e.path() + " with unknown " + p.name() + " returns " + code,
          CaseFactory.withPath(valid, path), code, true, notes));
    }
    return out;
  }
}
