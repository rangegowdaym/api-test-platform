package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

/** Right status, wrong data in the body. */
public class AssertionMismatchRule implements RcaRule {
  public String category() { return "FUNCTIONAL_BUG"; }
  public String owner() { return "API developer"; }

  public double score(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    boolean statusOk = a != null && a.equals(e.expectedStatus());
    String m = RcaRule.msg(e);
    if (statusOk && (m.contains("JSON path") || m.contains("Expected:") || m.contains("expected ["))) return 0.8;
    return 0;
  }
  public String summary(Evidence e, RcaContext c) { return e.endpoint() + " returned the right status but wrong data"; }
  public String probableCause(Evidence e, RcaContext c) {
    String m = RcaRule.msg(e);
    return "A field value differs from the test case expectation: " + (m.length() > 240 ? m.substring(0, 240) + "..." : m);
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Check whether the expectation in the test case is still valid; if it is, raise a bug with the request/response below.";
  }
}
