package com.qa.platform.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import com.qa.platform.model.TestCase;
import com.qa.platform.rca.rules.AssertionMismatchRule;
import com.qa.platform.rca.rules.AuthConfigRule;
import com.qa.platform.rca.rules.ContractDriftRule;
import com.qa.platform.rca.rules.EnvironmentRule;
import com.qa.platform.rca.rules.FlakyRule;
import com.qa.platform.rca.rules.PerformanceRule;
import com.qa.platform.rca.rules.ServerErrorRule;
import com.qa.platform.rca.rules.TestDataRule;
import com.qa.platform.rca.rules.TestDefectRule;
import com.qa.platform.rca.rules.WrongStatusRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads target/evidence/*.json + target/run-summary.json + history/, classifies every failure,
 * groups failures that share a root cause, and writes target/rca/rca.json + one bug ticket per group.
 */
public class RcaEngine {

  private final List<RcaRule> rules = List.of(
      new EnvironmentRule(), new AuthConfigRule(), new TestDataRule(), new ContractDriftRule(),
      new ServerErrorRule(), new PerformanceRule(), new WrongStatusRule(), new AssertionMismatchRule(),
      new TestDefectRule());
  private final FlakyRule flaky = new FlakyRule();

  private final Path evidenceDir;
  private final Path runSummary;
  private final Path historyDir;
  private final Path outDir;
  private final CaseStore cases;
  private final LlmRcaEnricher llm;

  public RcaEngine(Path evidenceDir, Path runSummary, Path historyDir, Path outDir, CaseStore cases, LlmClient llm) {
    this.evidenceDir = evidenceDir;
    this.runSummary = runSummary;
    this.historyDir = historyDir;
    this.outDir = outDir;
    this.cases = cases;
    this.llm = new LlmRcaEnricher(llm);
  }

  public record Report(String runId, String generatedAt, int failures, int groups,
                       Map<String, Long> byCategory, Map<String, Long> byOwner, long newFailures,
                       List<RcaEntry> entries) {}

  public Report run() throws IOException {
    JsonNode summary = Files.exists(runSummary) ? Json.MAPPER.readTree(runSummary.toFile()) : Json.MAPPER.createObjectNode();
    String runId = summary.path("runId").asText("run-" + Instant.now().toEpochMilli());
    RunHistory history = new RunHistory(historyDir);
    Map<String, TestCase> byId = cases.readAllById();

    List<Evidence> failures = readEvidence().stream().filter(e -> "FAILED".equals(e.status())).toList();

    // 1. classify every failure
    List<RcaEntry> single = new ArrayList<>();
    Map<String, Evidence> evidenceByCase = new LinkedHashMap<>();
    for (Evidence e : failures) {
      evidenceByCase.put(e.caseId(), e);
      List<String> prev = history.previous(e.caseId());
      RcaContext ctx = new RcaContext(byId.get(e.caseId()), prev);
      single.add(classify(e, ctx, runId, history));
    }

    // 2. group by signature: one outage = one RCA entry with related cases
    Map<String, List<RcaEntry>> groups = single.stream()
        .collect(Collectors.groupingBy(RcaEntry::signature, LinkedHashMap::new, Collectors.toList()));
    List<RcaEntry> entries = new ArrayList<>();
    for (List<RcaEntry> g : groups.values()) {
      RcaEntry head = g.get(0);
      List<String> related = g.stream().skip(1).map(RcaEntry::caseId).toList();
      RcaEntry grouped = related.isEmpty() ? head : withRelated(head, related);
      Evidence ev = evidenceByCase.get(head.caseId());
      grouped = llm.enrich(grouped, ev, specFragment(byId.get(head.caseId())));
      grouped = withTicket(grouped, BugTicketWriter.render(grouped, ev));
      entries.add(grouped);
    }
    entries.sort(Comparator.comparing(RcaEntry::newFailure).reversed().thenComparing(RcaEntry::confidence, Comparator.reverseOrder()));

    // 3. write outputs
    Files.createDirectories(outDir.resolve("tickets"));
    for (RcaEntry r : entries) Files.writeString(outDir.resolve("tickets").resolve(r.caseId() + ".md"), r.bugTicket());
    Report report = new Report(runId, Instant.now().toString(), failures.size(), entries.size(),
        count(entries, RcaEntry::category), count(entries, RcaEntry::owner),
        entries.stream().filter(RcaEntry::newFailure).count(), entries);
    Json.write(outDir.resolve("rca.json"), report);

    // 4. remember this run for flaky / new-failure detection next time
    Map<String, String> statuses = new LinkedHashMap<>();
    for (JsonNode s : summary.path("scenarios")) statuses.put(s.path("caseId").asText(), s.path("status").asText());
    for (Evidence e : failures) statuses.putIfAbsent(e.caseId(), "FAILED");
    if (!statuses.isEmpty()) history.append(runId, statuses, Instant.now().toString());
    return report;
  }

  RcaEntry classify(Evidence e, RcaContext ctx, String runId, RunHistory history) {
    RcaRule best = null;
    double bestScore = 0;
    List<String> others = new ArrayList<>();
    for (RcaRule r : rules) {
      double s = r.score(e, ctx);
      if (s > bestScore) {
        if (best != null && bestScore >= 0.3) others.add(best.category());
        best = r;
        bestScore = s;
      } else if (s >= 0.3) {
        others.add(r.category());
      }
    }
    if (best == null) {
      best = new TestDefectRule();
      bestScore = 0.3;
    }
    double flakyScore = flaky.score(e, ctx);
    String summary = best.summary(e, ctx);
    String cause = best.probableCause(e, ctx);
    String fix = best.suggestedFix(e, ctx);
    String category = best.category();
    String owner = best.owner();
    if (flakyScore >= 0.7 && !"ENVIRONMENT".equals(category)) {
      others.add(0, category);
      category = flaky.category();
      owner = flaky.owner();
      summary = flaky.summary(e, ctx);
      cause = flaky.probableCause(e, ctx) + " Last failure looked like: " + best.summary(e, ctx) + ".";
      fix = flaky.suggestedFix(e, ctx);
      bestScore = flakyScore;
    }

    List<String> hist = new ArrayList<>(ctx.history());
    hist.add("FAILED");
    boolean isNew = ctx.history().isEmpty() || !"FAILED".equals(ctx.history().get(ctx.history().size() - 1));
    String signature = category + "|" + e.endpoint() + "|" + normalise(e);
    if ("ENVIRONMENT".equals(category)) signature = category + "|" + host(e);   // one outage, many endpoints

    return new RcaEntry(e.caseId(), null, e.scenario(), e.endpoint(), category, round(bestScore), signature,
        summary, cause, fix, owner, isNew, history.firstFailedRunOfStreak(e.caseId(), runId),
        hist.subList(Math.max(0, hist.size() - 5), hist.size()),
        "evidence/" + e.caseId() + ".json", e.curl(), null, others.stream().distinct().limit(3).toList(), "rules");
  }

  /** Strip ids, numbers and quotes so "expected 400 got 500 for id 123" and "...id 456" group together. */
  private static String normalise(Evidence e) {
    String m = (e.response() == null ? "no-response" : String.valueOf(e.response().status())) + " " + (e.errorType() == null ? "" : e.errorType());
    return m.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<uuid>").replaceAll("\\d{4,}", "<n>").trim();
  }

  private static String host(Evidence e) {
    if (e.request() == null || e.request().url() == null) return "unknown-host";
    return e.request().url().replaceAll("^(https?://[^/]+).*", "$1");
  }

  private List<Evidence> readEvidence() throws IOException {
    if (!Files.exists(evidenceDir)) return List.of();
    List<Evidence> list = new ArrayList<>();
    try (Stream<Path> files = Files.list(evidenceDir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        list.add(Json.MAPPER.readValue(f.toFile(), Evidence.class));
      }
    }
    return list;
  }

  static String specFragment(TestCase tc) {
    if (tc == null) return "";
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("endpoint", tc.source().method() + " " + tc.source().path());
    m.put("testCase", tc.title());
    m.put("expected", tc.expected());
    return Json.pretty(m);
  }

  private static Map<String, Long> count(List<RcaEntry> entries, java.util.function.Function<RcaEntry, String> key) {
    return entries.stream().collect(Collectors.groupingBy(key, TreeMap::new,
        Collectors.summingLong(r -> 1L + (r.relatedCaseIds() == null ? 0 : r.relatedCaseIds().size()))));
  }

  private static double round(double d) { return Math.round(d * 100) / 100.0; }

  private static RcaEntry withRelated(RcaEntry r, List<String> related) {
    return new RcaEntry(r.caseId(), related, r.scenario(), r.endpoint(), r.category(), r.confidence(), r.signature(),
        r.summary() + " (+" + related.size() + " more test" + (related.size() == 1 ? "" : "s") + " with the same cause)",
        r.probableCause(), r.suggestedFix(), r.owner(), r.newFailure(), r.firstFailedRun(), r.history(),
        r.evidenceRef(), r.curl(), r.bugTicket(), r.otherPossibleCauses(), r.analysedBy());
  }

  static RcaEntry withTicket(RcaEntry r, String ticket) {
    return new RcaEntry(r.caseId(), r.relatedCaseIds(), r.scenario(), r.endpoint(), r.category(), r.confidence(), r.signature(),
        r.summary(), r.probableCause(), r.suggestedFix(), r.owner(), r.newFailure(), r.firstFailedRun(), r.history(),
        r.evidenceRef(), r.curl(), ticket, r.otherPossibleCauses(), r.analysedBy());
  }
}
