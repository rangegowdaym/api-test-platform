package com.qa.platform.ai;

import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.rca.RcaFiles;
import java.util.List;
import java.util.Map;

/** RCA narrative per root cause (prompts/rca-analysis.md). Category, owner and grouping are never changed. */
final class RcaNarrativeTask implements AiTask {
  private final RcaFiles rca = new RcaFiles(Workspace.RCA, Workspace.EVIDENCE, new CaseStore(Workspace.TESTCASES));

  public String name() { return "rca-narrative"; }
  public String format() { return "json"; }
  public Map<String, String> args() { return Map.of(); }
  public int maxTokens() { return 1200; }
  public String describe() {
    return "Rewrite the summary, probable cause and fix of each root cause in target/rca/rca.json from the masked "
        + "evidence. Answer per request: {\"summary\":\"...\",\"probableCause\":\"...\",\"suggestedFix\":\"...\",\"confidence\":0.0}. "
        + "The category is kept; if the evidence contradicts it, start probableCause with \"Category may be wrong:\".";
  }

  public List<AiRequest> requests() throws Exception {
    return rca.narrativeRequests().stream().map(r -> new AiRequest(r.caseId(), "rca-analysis", r.vars())).toList();
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    return rca.applyNarrative(request.id(), AiText.json(answer), "rules+" + by);
  }

  public String finish() throws Exception { return rca.refreshReport(); }
}
