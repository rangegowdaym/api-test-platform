package com.qa.platform.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * history/results.jsonl: one line per scenario per run {"runId","caseId","status","at"}.
 * Commit it (or cache it in CI) so flaky and new-failure detection work across runs.
 */
public class RunHistory {
  private static final int KEEP = 10;
  private final Path file;
  private final Map<String, List<String[]>> byCase = new LinkedHashMap<>();   // caseId -> [runId, status]

  public RunHistory(Path dir) {
    this.file = dir.resolve("results.jsonl");
    if (Files.exists(file)) {
      try {
        for (String line : Files.readAllLines(file)) {
          if (line.isBlank()) continue;
          JsonNode n = Json.MAPPER.readTree(line);
          byCase.computeIfAbsent(n.path("caseId").asText(), k -> new ArrayList<>())
              .add(new String[] {n.path("runId").asText(), n.path("status").asText()});
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** Statuses of previous runs, oldest first, at most KEEP. */
  public List<String> previous(String caseId) {
    List<String[]> runs = byCase.getOrDefault(caseId, List.of());
    return runs.subList(Math.max(0, runs.size() - KEEP), runs.size()).stream().map(r -> r[1]).toList();
  }

  /** The run id of the first failure in the current unbroken streak of failures (null if it passed last time). */
  public String firstFailedRunOfStreak(String caseId, String currentRun) {
    List<String[]> runs = byCase.getOrDefault(caseId, List.of());
    String first = currentRun;
    for (int i = runs.size() - 1; i >= 0; i--) {
      if (!"FAILED".equals(runs.get(i)[1])) break;
      first = runs.get(i)[0];
    }
    return first;
  }

  public void append(String runId, Map<String, String> statusByCase, String at) {
    try {
      Files.createDirectories(file.getParent());
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, String> e : statusByCase.entrySet()) {
        Map<String, String> line = new LinkedHashMap<>();
        line.put("runId", runId);
        line.put("caseId", e.getKey());
        line.put("status", e.getValue());
        line.put("at", at);
        sb.append(Json.MAPPER.writeValueAsString(line)).append('\n');
      }
      Files.writeString(file, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
