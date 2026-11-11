package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class TestDefectRule implements RcaRule {
  public String category() { return "TEST_DEFECT"; }
  public String owner() { return "QA (framework)"; }

  public double score(Evidence e, RcaContext c) {
    String t = RcaRule.type(e) + " " + RcaRule.msg(e);
    if (t.matches("(?s).*(UndefinedStep|AmbiguousStep|NullPointerException|ClassCastException|IllegalArgumentException: Unknown|"
        + "PathNotFound|JsonPathException|Test case not found|IndexOutOfBounds).*")) return 0.85;
    if (e.response() == null && !t.isBlank()) return 0.5;    // failed before any HTTP call, and not a network error
    return 0;
  }
  public String summary(Evidence e, RcaContext c) { return "The test itself broke before checking " + e.endpoint(); }
  public String probableCause(Evidence e, RcaContext c) {
    return "A step definition, JSONPath or test case JSON is wrong: " + RcaRule.type(e) + ": " + RcaRule.msg(e);
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Open the stack trace below, fix the step or the test case, and run this scenario alone: "
        + "mvn test -Dcucumber.filter.tags=\"@" + e.caseId() + "\"";
  }
}
