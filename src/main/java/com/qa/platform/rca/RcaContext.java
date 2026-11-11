package com.qa.platform.rca;

import com.qa.platform.model.TestCase;
import java.util.List;

/** What a rule may look at besides the evidence: the approved case and its recent results (oldest first). */
public record RcaContext(TestCase testCase, List<String> history) {
  public long recentPasses() { return history.stream().filter("PASSED"::equals).count(); }
  public boolean hasFlipFlopped() {
    for (int i = 1; i < history.size(); i++) if (!history.get(i).equals(history.get(i - 1))) return true;
    return false;
  }
}
