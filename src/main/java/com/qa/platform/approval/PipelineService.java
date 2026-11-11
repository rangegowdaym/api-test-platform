package com.qa.platform.approval;

import com.qa.platform.codegen.CodegenService;
import com.qa.platform.common.Workspace;
import com.qa.platform.feature.FeatureWriter;
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
 * The approval UI's pipeline buttons, in order:
 * <ol>
 *   <li><b>Generate features</b> - APPROVED cases only ({@link FeatureWriter}), plus a check that every
 *       {@code When I call the <tag> operation} step already has a generated step definition</li>
 *   <li><b>Generate services &amp; steps</b> - {@code <Tag>Api} + {@code <Tag>Steps} for every spec the cases came from</li>
 *   <li><b>Execute tests</b> - see {@link TestRunService}</li>
 * </ol>
 * Same code as the CLI commands {@code features} and {@code services}: the UI never calls an agent.
 */
@Service
public class PipelineService {

  private static final Pattern TAG = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_.\\-]+)");
  private static final Pattern ANNOTATION = Pattern.compile("@When\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)");

  private final CaseStore store;

  public PipelineService(CaseStore store) { this.store = store; }

  public record FeaturesResult(int features, int scenarios, List<String> files, List<String> missingSteps, String next) {}

  public record SpecResult(String spec, String status, int apiClasses, int operations, int stepClasses, int steps, String message) {}

  public record CodegenResult(List<SpecResult> specs, int files, List<String> missingSteps, String next) {}

  public synchronized FeaturesResult generateFeatures() throws IOException {
    List<TestCase> all = store.readAll();
    FeatureWriter.Result r = new FeatureWriter().write(all, Workspace.FEATURES);
    List<String> missing = missingSteps(all);
    String next = r.scenarios() == 0 ? "No APPROVED cases yet: approve some first."
        : missing.isEmpty() ? "Step definitions are in place: Execute tests."
        : "Generate services & steps: " + missing.size() + " operation(s) have no step definition yet.";
    return new FeaturesResult(r.features(), r.scenarios(),
        r.files().stream().map(p -> Workspace.ROOT.relativize(p.toAbsolutePath()).toString()).toList(), missing, next);
  }

  /** Api classes + step definitions for each spec referenced by a test case (or every file in specs/). */
  public synchronized CodegenResult generateServices() throws IOException {
    List<SpecResult> results = new ArrayList<>();
    int files = 0;
    for (String spec : specs()) {
      try {
        CodegenService.Result r = new CodegenService().generate(spec);
        results.add(new SpecResult(r.spec(), "OK", r.apiClasses(), r.operations(), r.stepClasses(), r.steps(), null));
        files += r.files().size();
      } catch (Exception e) {
        results.add(new SpecResult(spec, "ERROR", 0, 0, 0, 0, e.getMessage()));
      }
    }
    List<String> missing = missingSteps(store.readAll());
    String next = results.isEmpty() ? "No spec found: import one first (specs/ is empty)."
        : missing.isEmpty() ? "Ready: Execute tests (mvn test compiles the generated classes)."
        : "Still missing steps for: " + String.join(", ", missing) + " (is their spec in specs/?)";
    return new CodegenResult(results, files, missing, next);
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

  /** "orders createOrder" for every approved case whose When-step has no generated definition yet. */
  List<String> missingSteps(List<TestCase> all) throws IOException {
    Set<String> defined = definedSteps();
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> used : FeatureWriter.usedSteps(all).entrySet()) {
      if (!defined.contains(used.getKey())) missing.add(used.getValue());
    }
    return missing;
  }

  /** Step texts of the generated @When annotations, unescaped back to plain feature text. */
  private static Set<String> definedSteps() throws IOException {
    Set<String> out = new LinkedHashSet<>();
    Path dir = Workspace.ROOT.resolve("src/test/java/com/qa/automation/steps/generated");
    if (!Files.isDirectory(dir)) return out;
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith("Steps.java")).toList()) {
        Matcher m = ANNOTATION.matcher(Files.readString(f));
        while (m.find()) {
          String javaUnescaped = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
          out.add(javaUnescaped.replaceAll("\\\\([(){}/\\\\])", "$1"));   // undo StepGenerator.cucumberLiteral
        }
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
