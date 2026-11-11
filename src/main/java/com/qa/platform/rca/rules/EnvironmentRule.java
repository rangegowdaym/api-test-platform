package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;
import java.util.Set;

public class EnvironmentRule implements RcaRule {
  private static final Set<Integer> GATEWAY = Set.of(502, 503, 504);
  public String category() { return "ENVIRONMENT"; }
  public String owner() { return "DevOps / platform"; }

  public double score(Evidence e, RcaContext c) {
    String t = RcaRule.type(e) + " " + RcaRule.msg(e);
    if (t.matches("(?s).*(ConnectException|UnknownHostException|NoRouteToHost|Connection refused|SocketTimeout|ConnectTimeout|SSLHandshake|HttpHostConnect).*")) return 0.95;
    Integer a = RcaRule.actual(e);
    if (a != null && GATEWAY.contains(a)) return 0.85;
    return 0;
  }
  public String summary(Evidence e, RcaContext c) {
    Integer a = RcaRule.actual(e);
    return e.endpoint() + (a == null ? " could not be reached (" + shortType(e) + ")" : " returned " + a + " from the gateway");
  }
  public String probableCause(Evidence e, RcaContext c) {
    return "The service or its gateway was down, restarting or unreachable from the test runner during the run. The test itself did not get a real answer.";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Check the service health and deployment logs for the run time; confirm the base URL in config/env/" + e.env()
        + ".properties; re-run once the environment is healthy. Do not raise a functional bug for this.";
  }
  private static String shortType(Evidence e) {
    String t = RcaRule.type(e);
    return t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
  }
}
