package com.qa.platform.generate.rules;

import com.qa.platform.generate.CaseFactory;
import com.qa.platform.generate.CaseRule;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.List;

public class PositiveRule implements CaseRule {
  @Override public String category() { return "POSITIVE"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    int ok = Integer.parseInt(e.successCode());
    List<String> notes = new ArrayList<>();
    if (!e.responses().containsKey(e.successCode())) notes.add("No 2xx documented; expected 200 assumed.");
    return List.of(f.build(e, category(), null, "P1",
        e.method() + " " + e.path() + " with valid data returns " + ok,
        f.validRequest(e), ok, true, notes));
  }
}
