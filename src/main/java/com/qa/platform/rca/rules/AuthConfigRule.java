package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class AuthConfigRule implements RcaRule {
  public String category() { return "AUTH_CONFIG"; }
  public String owner() { return "QA (test config)"; }

  public double score(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    boolean expectedAuthFailure = e.expectedStatus() != null && (e.expectedStatus() == 401 || e.expectedStatus() == 403);
    if (RcaRule.msg(e).contains("Token request failed")) return 0.95;
    if (a != null && (a == 401 || a == 403) && !expectedAuthFailure) return c.recentPasses() > 0 ? 0.9 : 0.75;
    return 0;
  }
  public String summary(Evidence e, RcaContext c) {
    return e.endpoint() + " rejected the test credentials (" + RcaRule.actual(e) + ") on a case that expects access";
  }
  public String probableCause(Evidence e, RcaContext c) {
    return "The token or API key used by the run is expired, missing a scope/role, or points at the wrong auth server for env '" + e.env() + "'.";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Check the auth profile in config/env/" + e.env() + ".properties and the CI secrets (client id/secret, scopes). "
        + "If permissions changed on purpose, update the test user's role.";
  }
}
