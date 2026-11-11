package com.qa.platform.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.ai.AiRunner;
import com.qa.platform.ai.AiTasks;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * After a run: collects the failures (target/evidence + target/run-summary.json), records the run in history/,
 * and writes target/rca/rca.json.
 *
 * <p>The ROOT CAUSE ANALYSIS ITSELF IS DONE BY AI (task "rca", prompts/rca.md): category, owner, grouping of
 * failures with the same cause, explanation, fix and bug ticket. With an LLM token and --llm it runs right away;
 * otherwise this class writes a placeholder per failure (category UNCLASSIFIED) and the IDE agent finishes it
 * with {@code ai rca --mode agent} / {@code /rca}. Testers review the result in the report.
 */
public class RcaEngine {

  private final Path evidenceDir;
  private final Path runSummary;
  private final Path historyDir;
  private final Path outDir;
  private final LlmClient llm;

  public RcaEngine(Path evidenceDir, Path runSummary, Path historyDir, Path outDir, CaseStore cases, LlmClient llm) {
    this.evidenceDir = evidenceDir;
    this.runSummary = runSummary;
    this.historyDir = historyDir;
    this.outDir = outDir;
    this.llm = llm;
  }

  public record Report(String runId, String generatedAt, int failures, int groups,
                       Map<String, Long> byCategory, Map<String, Long> byOwner, long newFailures,
                       List<RcaEntry> entries) {}

  public Report run() throws IOException {
    JsonNode summary = Files.exists(runSummary) ? Json.MAPPER.readTree(runSummary.toFile()) : Json.MAPPER.createObjectNode();
    String runId = summary.path("runId").asText("run-" + Instant.now().toEpochMilli());
    RunHistory history = new RunHistory(historyDir);
    List<Evidence> failures = failures(evidenceDir);

    // placeholder per failure until the AI has classified and grouped them
    List<RcaEntry> entries = new ArrayList<>();
    for (Evidence e : failures) {
      List<String> prev = history.previous(e.caseId());
      boolean isNew = prev.isEmpty() || !"FAILED".equals(prev.get(prev.size() - 1));
      entries.add(new RcaEntry(e.caseId(), List.of(), e.scenario(), e.endpoint(), "UNCLASSIFIED", 0, "unclassified|" + e.caseId(),
          abbreviate(e.errorType() + ": " + e.errorMessage(), 140), "Not analysed yet: run /rca (IDE agent) or post-run --llm.", "",
          "unassigned", isNew, history.firstFailedRunOfStreak(e.caseId(), runId), prev.subList(Math.max(0, prev.size() - 5), prev.size()),
          "evidence/" + e.caseId() + ".json", e.curl(), null, List.of(), "none"));
    }
    Report report = report(runId, failures.size(), entries);
    Files.createDirectories(outDir);
    Json.write(outDir.resolve("failures.json"), report);   // facts per failure, input of the AI "rca" task
    Json.write(outDir.resolve("rca.json"), report);

    // remember this run (the AI uses it to spot flaky and new failures)
    Map<String, String> statuses = new LinkedHashMap<>();
    for (JsonNode s : summary.path("scenarios")) statuses.put(s.path("caseId").asText(), s.path("status").asText());
    for (Evidence e : failures) statuses.putIfAbsent(e.caseId(), "FAILED");
    if (!statuses.isEmpty()) history.append(runId, statuses, Instant.now().toString());

    if (!failures.isEmpty() && llm.enabled()) {
      try {
        System.out.print(AiRunner.format(new AiRunner().run(AiTasks.create("rca", Map.of()), AiRunner.Mode.LLM)));
        return Json.MAPPER.readValue(outDir.resolve("rca.json").toFile(), Report.class);
      } catch (Exception ex) {
        System.err.println("[rca] AI analysis failed, placeholders kept: " + ex.getMessage());
      }
    }
    return report;
  }

  /** Counts derived from the entries (never taken from the AI answer). */
  public static Report report(String runId, int failures, List<RcaEntry> entries) {
    return new Report(runId, Instant.now().toString(), failures, entries.size(),
        count(entries, RcaEntry::category), count(entries, RcaEntry::owner),
        entries.stream().filter(RcaEntry::newFailure).count(), entries);
  }

  public static List<Evidence> failures(Path evidenceDir) throws IOException {
    if (!Files.exists(evidenceDir)) return List.of();
    List<Evidence> list = new ArrayList<>();
    try (Stream<Path> files = Files.list(evidenceDir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        Evidence e = Json.MAPPER.readValue(f.toFile(), Evidence.class);
        if ("FAILED".equals(e.status())) list.add(e);
      }
    }
    return list;
  }

  private static Map<String, Long> count(List<RcaEntry> entries, java.util.function.Function<RcaEntry, String> key) {
    return entries.stream().collect(Collectors.groupingBy(key, TreeMap::new,
        Collectors.summingLong(r -> 1L + (r.relatedCaseIds() == null ? 0 : r.relatedCaseIds().size()))));
  }

  private static String abbreviate(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "..."; }
}
