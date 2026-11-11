package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class TestDataRule implements RcaRule {
  public String category() { return "TEST_DATA"; }
  public String owner() { return "QA (test data)"; }

  public double score(Evidence e, RcaContext c) {
    String m = RcaRule.msg(e);
    if (m.contains("Missing test data")) return 0.98;
    Integer a = RcaRule.actual(e);
    boolean positive = e.expectedStatus() != null && e.expectedStatus() / 100 == 2;
    if (positive && a != null && (a == 404 || a == 409 || a == 410)) return 0.8;
    String body = e.response() == null || e.response().body() == null ? "" : e.response().body().toLowerCase();
    if (positive && a != null && a / 100 == 4 && body.matches("(?s).*(already exists|duplicate|not found|does not exist).*")) return 0.75;
    return 0;
  }
  public String summary(Evidence e, RcaContext c) {
    return RcaRule.msg(e).contains("Missing test data")
        ? e.endpoint() + " could not run: test data is not configured"
        : e.endpoint() + " failed because the data it needs is missing or already used (" + RcaRule.actual(e) + ")";
  }
  public String probableCause(Evidence e, RcaContext c) {
    return "The ids or records the case depends on do not exist in env '" + e.env() + "', were deleted by another test, or were left over from an earlier run.";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Set the testdata.* values in config/env/" + e.env() + ".properties, or create the record in a setup step and save its id "
        + "(\"And I save \\\"$.id\\\" as \\\"orderId\\\"\"). Make the test clean up what it creates.";
  }
}
