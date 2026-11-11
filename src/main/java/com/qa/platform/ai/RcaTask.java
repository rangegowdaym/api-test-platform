package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import com.qa.platform.model.TestCase;
import com.qa.platform.rca.RcaEngine;
import com.qa.platform.report.ReportRcaMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The AI does the RCA (prompts/rca.md): category, owner, grouping, explanation, fix, bug ticket, triage note.
 * Facts are not taken from the answer: endpoint, curl, history, new/first-failed come from the evidence; the counts
 * are recomputed. Every failed case ends up in exactly one entry (missing ones stay UNCLASSIFIED).
 */
final class RcaTask implements AiTask {
  static final Set<String> CATEGORIES = Set.of("ENVIRONMENT", "AUTH_CONFIG", "CONTRACT_DRIFT", "SERVER_ERROR",
      "FUNCTIONAL_BUG", "TEST_DATA", "PERFORMANCE", "FLAKY", "TEST_DEFECT");

  public String name() { return "rca"; }
  public String format() { return "json"; }
  public Map<String, String> args() { return Map.of(); }
  public int maxTokens() { return 8000; }
  public String describe() {
    return "Root cause analysis of the last run: you decide category, owner, grouping, explanation, fix and bug ticket "
        + "for every failure, plus the triage note. Testers review it in reports/index.html (RCA view).";
  }

  private RcaEngine.Report current() throws Exception {
    Path f = Workspace.RCA.resolve("rca.json");
    if (!Files.exists(f)) throw new IllegalStateException("No target/rca/rca.json: run post-run after the tests first");
    return Json.MAPPER.readValue(f.toFile(), RcaEngine.Report.class);
  }

  public List<AiRequest> requests() throws Exception {
    List<Evidence> failures = RcaEngine.failures(Workspace.EVIDENCE);
    if (failures.isEmpty()) return List.of();
    Map<String, TestCase> cases = new CaseStore(Workspace.TESTCASES).readAllById();
    Map<String, RcaEntry> facts = facts(current());
    StringBuilder sb = new StringBuilder();
    for (Evidence e : failures.subList(0, Math.min(60, failures.size()))) {
      TestCase tc = cases.get(e.caseId());
      RcaEntry f = facts.get(e.caseId());
      sb.append("### ").append(e.caseId()).append(" - ").append(e.endpoint()).append('\n')
          .append("scenario: ").append(e.scenario()).append('\n')
          .append("history: ").append(f == null || f.history() == null ? "[]" : f.history()).append('\n')
          .append("expected (approved case): ").append(tc == null ? "?" : Json.MAPPER.writeValueAsString(tc.expected())).append('\n')
          .append("evidence:\n").append(AiText.clean(Json.pretty(e), 3500)).append("\n\n");
    }
    if (failures.size() > 60) sb.append("(").append(failures.size() - 60).append(" more failures not shown; group them by endpoint/error)\n");
    return List.of(new AiRequest("run", "rca", Map.of(
        "run_id", current().runId(), "failure_count", String.valueOf(failures.size()), "failures", sb.toString())));
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    JsonNode a = AiText.json(answer);
    RcaEngine.Report report = current();
    Map<String, RcaEntry> facts = facts(report);
    Set<String> covered = new LinkedHashSet<>();
    List<RcaEntry> entries = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (JsonNode n : a.path("entries")) {
      String id = n.path("caseId").asText("");
      RcaEntry f = facts.get(id);
      String category = n.path("category").asText("");
      if (f == null || covered.contains(id)) { problems.add("unknown or repeated case " + id); continue; }
      if (!CATEGORIES.contains(category)) { problems.add(id + ": unknown category " + category); continue; }
      List<String> related = new ArrayList<>();
      for (JsonNode r : n.path("relatedCaseIds")) {
        String rid = r.asText();
        if (facts.containsKey(rid) && !rid.equals(id) && !covered.contains(rid) && !related.contains(rid)) related.add(rid);
      }
      covered.add(id);
      covered.addAll(related);
      List<String> others = new ArrayList<>();
      for (JsonNode o : n.path("otherPossibleCauses")) others.add(AiText.clean(o.asText(), 300));
      entries.add(new RcaEntry(id, related, f.scenario(), f.endpoint(), category,
          Math.max(0, Math.min(1, n.path("confidence").asDouble(0.5))), category.toLowerCase() + "|" + f.endpoint(),
          AiText.clean(n.path("summary").asText(""), 200), AiText.clean(n.path("probableCause").asText(""), 1500),
          AiText.clean(n.path("suggestedFix").asText(""), 1500), AiText.clean(n.path("owner").asText("unassigned"), 60),
          f.newFailure(), f.firstFailedRun(), f.history(), f.evidenceRef(), f.curl(),
          AiText.clean(n.path("bugTicket").asText(""), 20000), others, "ai:" + by));
    }
    if (entries.isEmpty()) throw new IllegalArgumentException("no usable entry: " + String.join("; ", problems));
    // failures the answer forgot stay visible as UNCLASSIFIED
    for (RcaEntry f : facts.values()) if (!covered.contains(f.caseId())) entries.add(f);

    Path tickets = Workspace.RCA.resolve("tickets");
    Files.createDirectories(tickets);
    for (RcaEntry e : entries) if (e.bugTicket() != null && !e.bugTicket().isBlank()) Files.writeString(tickets.resolve(e.caseId() + ".md"), e.bugTicket());
    Json.write(Workspace.RCA.resolve("rca.json"), RcaEngine.report(report.runId(), report.failures(), entries));
    String triage = AiText.unfence(a.path("triage").asText(""), "markdown");
    if (!triage.isBlank()) {
      Files.writeString(Workspace.RCA.resolve("triage.md"), "<!-- written by " + by + " (prompts/rca.md); reviewed by testers -->\n\n"
          + AiText.clean(triage, 40000) + "\n");
    }
    long unclassified = entries.stream().filter(e -> "UNCLASSIFIED".equals(e.category())).count();
    return entries.size() + " root causes" + (unclassified > 0 ? ", " + unclassified + " still UNCLASSIFIED" : "")
        + (problems.isEmpty() ? "" : " (ignored: " + String.join("; ", problems) + ")");
  }

  public String finish() throws Exception {
    return new ReportRcaMerger().merge(Workspace.REPORTS.resolve("index.html"), Workspace.RCA.resolve("rca.json")).message();
  }

  /** Facts per failed case, written by RcaEngine before any AI ran (target/rca/failures.json). */
  private static Map<String, RcaEntry> facts(RcaEngine.Report ignored) throws Exception {
    Path f = Workspace.RCA.resolve("failures.json");
    if (!Files.exists(f)) throw new IllegalStateException("No target/rca/failures.json: run post-run after the tests first");
    Map<String, RcaEntry> out = new LinkedHashMap<>();
    for (RcaEntry r : Json.MAPPER.readValue(f.toFile(), RcaEngine.Report.class).entries()) out.put(r.caseId(), r);
    return out;
  }
}
