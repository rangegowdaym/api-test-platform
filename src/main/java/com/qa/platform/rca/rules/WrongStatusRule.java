package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

/** Got a 2xx/4xx, but not the expected one (and not an auth or gateway case). */
public class WrongStatusRule implements RcaRule {
  public String category() { return "FUNCTIONAL_BUG"; }
  public String owner() { return "API developer"; }

  public double score(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    if (a == null || e.expectedStatus() == null || a.equals(e.expectedStatus()) || a >= 500 || a == 401 || a == 403) return 0;
    if (e.expectedStatus() / 100 == 4 && a / 100 == 2) return 0.9;      // invalid input accepted
    if (e.expectedStatus() / 100 == 2 && a / 100 == 4) return 0.55;     // could also be test data -> TestDataRule may win
    return 0.7;
  }
  public String summary(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    if (e.expectedStatus() / 100 == 4 && a / 100 == 2) return e.endpoint() + " accepted invalid input (" + a + ", expected " + e.expectedStatus() + ")";
    return e.endpoint() + " returned " + a + ", expected " + e.expectedStatus();
  }
  public String probableCause(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    String what = c.testCase() == null ? "" : " Test: " + c.testCase().title() + ".";
    if (e.expectedStatus() / 100 == 4 && a / 100 == 2) return "A validation rule from the spec is not enforced by the API." + what;
    return "The API behaviour differs from the spec for this case." + what;
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Confirm the expected behaviour with the spec owner. If the spec is right, raise the bug below; if not, fix the spec, "
        + "re-import it and re-approve the case.";
  }
}
