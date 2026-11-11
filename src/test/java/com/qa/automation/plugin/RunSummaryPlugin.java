package com.qa.automation.plugin;

import com.qa.automation.support.ErrorRegistry;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStepFinished;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Cucumber event listener:
 *  - clears target/evidence at the start of a run (no stale evidence in the RCA)
 *  - passes the failing step + exception to Hooks via ErrorRegistry
 *  - writes target/run-summary.json at the end (input to RCA and the report)
 */
public class RunSummaryPlugin implements ConcurrentEventListener {

  private final List<Map<String, Object>> scenarios = Collections.synchronizedList(new ArrayList<>());
  private Instant startedAt;

  @Override
  public void setEventPublisher(EventPublisher p) {
    p.registerHandlerFor(TestRunStarted.class, e -> {
      startedAt = e.getInstant();
      clearEvidence();
    });
    p.registerHandlerFor(TestStepFinished.class, e -> {
      if (e.getResult().getStatus() == Status.FAILED && e.getTestStep() instanceof PickleStepTestStep s) {
        ErrorRegistry.put(e.getTestCase().getId().toString(), s.getStep().getKeyword() + s.getStep().getText(), e.getResult().getError());
      } else if (e.getResult().getStatus() == Status.FAILED) {
        ErrorRegistry.put(e.getTestCase().getId().toString(), "hook", e.getResult().getError());
      }
    });
    p.registerHandlerFor(TestCaseFinished.class, e -> {
      Map<String, Object> s = new LinkedHashMap<>();
      String caseId = e.getTestCase().getTags().stream().filter(t -> t.startsWith("@TC-")).map(t -> t.substring(1))
          .findFirst().orElse(e.getTestCase().getName());
      s.put("caseId", caseId);
      s.put("name", e.getTestCase().getName());
      s.put("feature", e.getTestCase().getUri().toString().replaceAll(".*/", ""));
      s.put("tags", e.getTestCase().getTags());
      s.put("status", e.getResult().getStatus().name());
      s.put("durationMs", e.getResult().getDuration().toMillis());
      if (e.getResult().getError() != null) {
        String m = String.valueOf(e.getResult().getError().getMessage());
        s.put("error", m.length() > 500 ? m.substring(0, 500) + "..." : m);
      }
      scenarios.removeIf(x -> x.get("caseId").equals(caseId));   // a retried scenario keeps its last result
      scenarios.add(s);
    });
    p.registerHandlerFor(TestRunFinished.class, e -> write(e.getInstant()));
  }

  private void write(Instant finishedAt) {
    Map<String, Long> totals = new LinkedHashMap<>();
    List<Map<String, Object>> sorted = new ArrayList<>(scenarios);
    sorted.sort(Comparator.comparing(m -> String.valueOf(m.get("caseId"))));
    for (Map<String, Object> s : sorted) totals.merge(String.valueOf(s.get("status")), 1L, Long::sum);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", runId());
    out.put("env", System.getProperty("env", "qa"));
    out.put("tags", System.getProperty("cucumber.filter.tags", ""));
    out.put("startedAt", String.valueOf(startedAt));
    out.put("finishedAt", finishedAt.toString());
    out.put("durationMs", startedAt == null ? 0 : finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    out.put("total", sorted.size());
    out.put("totals", totals);
    out.put("scenarios", sorted);
    Json.write(Workspace.ROOT.resolve("target/run-summary.json"), out);
  }

  private static String runId() {
    String id = System.getProperty("run.id");
    if (id == null) id = System.getenv("RUN_ID");
    if (id == null && System.getenv("GITHUB_RUN_NUMBER") != null) id = "gh-" + System.getenv("GITHUB_RUN_NUMBER");
    if (id == null && System.getenv("BUILD_NUMBER") != null) id = "jenkins-" + System.getenv("BUILD_NUMBER");
    return id == null ? "local-" + Instant.now().toString().replaceAll("[:.]", "-") : id;
  }

  private static void clearEvidence() {
    Path dir = Workspace.EVIDENCE;
    if (!Files.exists(dir)) return;
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.toList()) Files.deleteIfExists(f);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
