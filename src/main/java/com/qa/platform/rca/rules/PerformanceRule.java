package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class PerformanceRule implements RcaRule {
  public String category() { return "PERFORMANCE"; }
  public String owner() { return "API developer / performance"; }

  public double score(Evidence e, RcaContext c) {
    return RcaRule.msg(e).contains("Response time") ? 0.9 : 0;
  }
  public String summary(Evidence e, RcaContext c) {
    Long t = e.response() == null ? null : e.response().timeMs();
    return e.endpoint() + " was too slow" + (t == null ? "" : " (" + t + " ms)");
  }
  public String probableCause(Evidence e, RcaContext c) {
    return "Slow query, cold start, or a slow downstream dependency; or the environment is under load during the run.";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Compare with the previous runs' timings. If it is consistently slow, profile the endpoint; if only this run, check environment load.";
  }
}
