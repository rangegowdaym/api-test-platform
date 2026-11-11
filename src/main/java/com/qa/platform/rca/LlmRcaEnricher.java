package com.qa.platform.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.Masking;
import com.qa.platform.common.PromptLibrary;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Optional: asks the LLM (GitHub Models by default) for a sharper summary/cause/fix (prompts/rca-analysis.md). The rule's CATEGORY and
 * OWNER always stay; the LLM only rewrites the narrative, and only if it answers with valid JSON.
 * Evidence is masked again here as a second line of defence.
 */
public class LlmRcaEnricher {

  private final LlmClient llm;

  public LlmRcaEnricher(LlmClient llm) { this.llm = llm; }

  public boolean enabled() { return llm.enabled(); }

  public RcaEntry enrich(RcaEntry base, Evidence e, String specFragment) {
    if (!llm.enabled()) return base;
    PromptLibrary.Prompt p = PromptLibrary.load("rca-analysis", vars(base, e, specFragment));
    Optional<JsonNode> answer = llm.complete(p.system(), p.user(), 1200).flatMap(LlmClient::extractJson);
    return answer.map(a -> merge(base, a, "rules+" + llm.model())).orElse(base);
  }

  /** The prompts/rca-analysis.md variables for one failure group (masked). Shared with the IDE-agent handoff. */
  static Map<String, String> vars(RcaEntry base, Evidence e, String specFragment) {
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("category", base.category());
    vars.put("rule_summary", base.summary());
    vars.put("rule_cause", base.probableCause());
    vars.put("endpoint", e == null ? base.endpoint() : e.endpoint());
    vars.put("expected_status", e == null ? "" : String.valueOf(e.expectedStatus()));
    vars.put("failed_step", e == null ? "" : String.valueOf(e.failedStep()));
    vars.put("error", e == null ? "" : Masking.text(trim(e.errorType() + ": " + e.errorMessage(), 2000)));
    vars.put("request", e == null ? "" : Masking.text(trim(Json.pretty(e.request()), 3000)));
    vars.put("response", e == null ? "" : Masking.text(trim(Json.pretty(e.response()), 4000)));
    vars.put("history", base.history() == null ? "" : String.join(",", base.history()));
    vars.put("spec_fragment", trim(specFragment, 3000));
    return vars;
  }

  /**
   * Applies a narrative answer {summary, probableCause, suggestedFix, confidence}. CATEGORY, OWNER, signature,
   * related cases and history are never taken from the answer. Text is masked and length-limited.
   */
  public static RcaEntry merge(RcaEntry base, JsonNode a, String analysedBy) {
    double conf = a.path("confidence").isNumber()
        ? Math.max(0, Math.min(0.99, a.path("confidence").asDouble())) : base.confidence();
    // a narrative that says the category may be wrong lowers confidence; it never raises it above the rule's
    boolean doubts = text(a, "probableCause", "").startsWith("Category may be wrong");
    double confidence = doubts ? Math.min(base.confidence(), conf) : Math.max(base.confidence(), conf);
    return new RcaEntry(base.caseId(), base.relatedCaseIds(), base.scenario(), base.endpoint(),
        base.category(), confidence, base.signature(),
        limit(text(a, "summary", base.summary()), 200), limit(text(a, "probableCause", base.probableCause()), 1200),
        limit(text(a, "suggestedFix", base.suggestedFix()), 1200), base.owner(), base.newFailure(), base.firstFailedRun(),
        base.history(), base.evidenceRef(), base.curl(), base.bugTicket(), base.otherPossibleCauses(), analysedBy);
  }

  private static String limit(String s, int max) {
    String m = Masking.text(s.strip());
    return m.length() <= max ? m : m.substring(0, max) + "...";
  }

  static String text(JsonNode a, String k, String def) {
    String v = a.path(k).asText("");
    return v.isBlank() ? def : v;
  }

  private static String trim(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated]";
  }
}
