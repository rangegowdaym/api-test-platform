package com.qa.platform.approval;

import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.TestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * The approval UI's pipeline: 1 Generate automation (AI) -> 2 Execute tests ({@link TestRunService}) -> 3 Open report.
 * "Generate automation" asks the AI (task "automation") to write the feature file, {@code <Tag>Api} and
 * {@code <Tag>Steps} for the APPROVED cases: answered now with an LLM token, else queued for Copilot (/ai).
 */
@Service
public class PipelineService {

  private static final Pattern TAG = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_.\\-]+)");

  private final CaseStore store;
  private final AiService ai;

  public PipelineService(CaseStore store, AiService ai) {
    this.store = store;
    this.ai = ai;
  }

  public com.qa.platform.ai.AiRunner.Result generateAutomation(String tag) throws Exception {
    long approved = store.readAll().stream().filter(t -> TestCase.APPROVED.equals(t.status())).count();
    if (approved == 0) throw new IllegalStateException("No APPROVED cases yet: approve some first.");
    return ai.run("automation", tag == null || tag.isBlank() ? Map.of() : Map.of("tag", tag));
  }

  /** Specs the stored cases were generated from that can still be read, else every file in specs/. */
  List<String> specs() throws IOException {
    Set<String> fromCases = new LinkedHashSet<>();
    for (TestCase t : store.readAll()) if (t.source() != null && t.source().spec() != null) fromCases.add(t.source().spec());
    List<String> out = new ArrayList<>();
    for (String s : fromCases) {
      if (s.startsWith("http://") || s.startsWith("https://")) out.add(s);
      else if (Files.exists(Workspace.SPECS.resolve(s))) out.add(Workspace.SPECS.resolve(s).toString());
    }
    if (out.isEmpty() && Files.isDirectory(Workspace.SPECS)) {
      try (Stream<Path> files = Files.list(Workspace.SPECS)) {
        files.filter(Files::isRegularFile).filter(p -> p.toString().matches("(?i).*\\.(ya?ml|json|md|markdown|html?)$"))
            .sorted().forEach(p -> out.add(p.toString()));
      }
    }
    return out;
  }

  /** Tags used in feature files (generated and manual) with the number of scenarios carrying each. */
  public Map<String, Integer> featureTags() throws IOException {
    Map<String, Integer> tags = new TreeMap<>();
    Path root = Workspace.ROOT.resolve("src/test/resources/features");
    if (!Files.isDirectory(root)) return tags;
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".feature")).toList()) {
        List<String> featureTags = List.of();
        List<String> pending = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
          String t = line.trim();
          if (t.startsWith("@")) {
            Matcher m = TAG.matcher(t);
            while (m.find()) pending.add("@" + m.group(1));
          } else if (t.startsWith("Feature:")) {
            featureTags = List.copyOf(pending);          // tags above Feature: apply to every scenario
            pending.clear();
          } else if (t.startsWith("Scenario") || t.startsWith("Example:")) {
            Set<String> scenario = new LinkedHashSet<>(featureTags);
            scenario.addAll(pending);
            scenario.forEach(x -> tags.merge(x, 1, Integer::sum));
            pending.clear();
          } else if (!t.isEmpty() && !t.startsWith("#")) {
            pending.clear();                             // tags only stick to the next keyword line
          }
        }
      }
    }
    return tags;
  }

  /** config/env/*.properties -> ["dev", "qa", "stage"] */
  public List<String> environments() throws IOException {
    Path dir = Workspace.CONFIG.resolve("env");
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".properties"))
          .filter(n -> !n.endsWith(".local.properties"))   // machine overrides, not environments
          .map(n -> n.substring(0, n.length() - ".properties".length())).sorted().toList();
    }
  }

  public Map<String, Object> options() throws IOException {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("environments", environments());
    out.put("tags", featureTags());
    out.put("features", countFeatures());
    return out;
  }

  private static long countFeatures() throws IOException {
    Path root = Workspace.ROOT.resolve("src/test/resources/features");
    if (!Files.isDirectory(root)) return 0;
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(p -> p.toString().endsWith(".feature")).count();
    }
  }
}
