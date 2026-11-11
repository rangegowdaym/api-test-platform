package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.generate.ProposalImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Business test cases the rules cannot know (prompts/testcase-augment.md) -> testcases/ as PENDING_REVIEW.
 * Schema-checked by {@link ProposalImporter}; never approved: a human reviews them in the approval UI.
 */
final class TestcaseAugmentTask implements AiTask {
  private final String spec;
  private final String context;

  TestcaseAugmentTask(String spec, String context) {
    if (spec == null || spec.isBlank()) throw new IllegalArgumentException("testcase-augment needs --spec");
    this.spec = spec;
    this.context = context == null ? "" : context;
  }

  public String name() { return "testcase-augment"; }
  public String format() { return "json"; }
  public Map<String, String> args() { return Map.of("spec", spec, "context", context); }
  public int maxTokens() { return 8000; }
  public String describe() {
    return "Propose CUSTOM business test cases for " + spec + ". Answer: a JSON array of test cases following "
        + "src/main/resources/schemas/testcase.schema.json. They are imported as PENDING_REVIEW for a human to approve.";
  }

  public List<AiRequest> requests() throws Exception {
    SpecText.Spec s = SpecText.load(spec, 60000);
    var ids = new TreeSet<>(new CaseStore(Workspace.TESTCASES).readBySpec(s.model().specName()).keySet());
    return List.of(new AiRequest(s.model().specName(), "testcase-augment", Map.of(
        "spec", s.text(), "existing_ids", String.join(", ", ids), "context", Masking.text(context))));
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    JsonNode cases = AiText.json(answer);
    Path file = Workspace.TESTCASES.resolve("_proposed").resolve(AiText.safeId(request.id()) + "-ai.json");
    Files.createDirectories(file.getParent());
    Json.write(file, cases);
    var r = new ProposalImporter(new CaseStore(Workspace.TESTCASES)).importCases(cases, "ai-proposal:" + by, "from " + file.getFileName());
    if (r.added() == 0) throw new IllegalArgumentException("no valid new case: " + String.join("; ", r.skipped()));
    return r.added() + " proposed cases added as PENDING_REVIEW" + (r.skipped().isEmpty() ? "" : ", skipped: " + String.join("; ", r.skipped()))
        + " (review them in the approval UI, filter Pending review)";
  }
}
