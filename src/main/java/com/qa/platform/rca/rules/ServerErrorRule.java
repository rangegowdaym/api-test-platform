package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class ServerErrorRule implements RcaRule {
  public String category() { return "SERVER_ERROR"; }
  public String owner() { return "API developer"; }

  public double score(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    if (a == null || a < 500 || a == 502 || a == 503 || a == 504) return 0;
    return e.expectedStatus() != null && e.expectedStatus() / 100 == 4 ? 0.95 : 0.85;
  }
  public String summary(Evidence e, RcaContext c) {
    return e.endpoint() + " returned " + RcaRule.actual(e) + " instead of " + e.expectedStatus();
  }
  public String probableCause(Evidence e, RcaContext c) {
    boolean negative = e.expectedStatus() != null && e.expectedStatus() / 100 == 4;
    return negative
        ? "Invalid input reached the server unhandled: input validation is missing and an exception escaped as a 500."
        : "An unhandled exception on the server for a valid request (null value, failing downstream call or DB error).";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Find the request in the service logs at the run time (use the curl below to reproduce). "
        + "Add validation and map it to a 4xx with an error body, or fix the failing dependency.";
  }
}
