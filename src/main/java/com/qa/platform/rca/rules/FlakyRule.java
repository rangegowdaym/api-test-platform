package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

/** Scores on history only; the engine adds it on top of the primary category when it applies. */
public class FlakyRule implements RcaRule {
  public String category() { return "FLAKY"; }
  public String owner() { return "QA (stabilise the test)"; }

  public double score(Evidence e, RcaContext c) {
    if (c.history().size() < 3) return 0;
    long passes = c.recentPasses();
    boolean flips = c.hasFlipFlopped();
    if (e.retries() > 0 && passes >= 1) return 0.8;
    return passes >= 2 && flips ? 0.7 : 0;
  }
  public String summary(Evidence e, RcaContext c) {
    return e.endpoint() + " fails intermittently (" + c.recentPasses() + " of the last " + c.history().size() + " runs passed)";
  }
  public String probableCause(Evidence e, RcaContext c) {
    return "Order-dependent or shared test data, timing/eventual consistency, or an unstable environment.";
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Give the scenario its own data and cleanup, wait for async state with a poll instead of a fixed sleep, "
        + "and tag it @flaky until fixed so it does not block releases.";
  }
}
