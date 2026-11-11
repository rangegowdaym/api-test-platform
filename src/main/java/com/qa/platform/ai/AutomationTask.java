package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.TestCase;
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

/**
 * Two AI tasks share this class (same files, same validation):
 * <ul>
 *   <li><b>automation</b> (prompts/automation.md): the AI writes the feature file, the {@code <Tag>Api} endpoint
 *       class and the {@code <Tag>Steps} step definitions for the APPROVED cases of each tag, choosing the assertions.</li>
 *   <li><b>heal</b> (prompts/heal.md): after a run, for tags with failures the RCA did not blame on the API
 *       (TEST_DATA, TEST_DEFECT, or not analysed yet), the AI fixes the automation: missing prerequisite data
 *       (scenarios create what they need), wrong step code, wrong request building. It may also answer "no change".</li>
 * </ul>
 * Validation before anything is written: fixed paths per tag, Java package matches the folder, every scenario tag
 * {@code @TC-...} is an approved case, a returned feature keeps one scenario per approved case and states each
 * approved expected status (so an expectation can never be weakened), no forbidden calls.
 */
final class AutomationTask implements AiTask {
  /** RCA categories the automation can fix; everything else (bugs, outages, auth...) is not healed. */
  static final Set<String> HEALABLE = Set.of("TEST_DATA", "TEST_DEFECT", "UNCLASSIFIED");
  private static final Path FEATURES = Workspace.FEATURES;
  private static final Path API = Workspace.ROOT.resolve("src/test/java/com/qa/automation/api/generated");
  private static final Path STEPS = Workspace.ROOT.resolve("src/test/java/com/qa/automation/steps/generated");
  private static final List<String> FRAMEWORK = List.of(
      "api/BaseApi.java", "client/ApiRequest.java", "client/ApiResponse.java", "client/HttpMethod.java",
      "services/ServiceFactory.java", "services/OperationService.java", "context/ScenarioContext.java",
      "assertions/ResponseAssert.java", "steps/ApiSteps.java");
  private static final Pattern FORBIDDEN = Pattern.compile(
      "Runtime\\.getRuntime|ProcessBuilder|System\\.exit|Thread\\.sleep|java\\.net\\.Socket|Files\\.delete|RestAssured\\.");
  private static final Pattern TC_TAG = Pattern.compile("@(TC-[A-Za-z0-9_.-]+)");

  private final String onlyTag;
  private final boolean heal;

  AutomationTask(String tag, boolean heal) {
    this.onlyTag = tag == null || tag.isBlank() ? null : tag;
    this.heal = heal;
  }

  public String name() { return heal ? "heal" : "automation"; }
  public String format() { return "files"; }
  public Map<String, String> args() { return onlyTag == null ? Map.of() : Map.of("tag", onlyTag); }
  public int maxTokens() { return 16000; }
  public String describe() {
    return heal
        ? "Fix the automation of the tags whose failures are TEST_DATA / TEST_DEFECT / not analysed (never the expectations). "
          + "An answer folder with only notes.md means: no automation change. Then re-run the tests."
        : "Write the feature file, <Tag>Api and <Tag>Steps for the APPROVED cases" + (onlyTag == null ? " of every tag" : " of tag " + onlyTag)
          + ". Then: mvn -q test -Dcucumber.execution.dry-run=true. Testers review the diff.";
  }

  private Map<String, List<TestCase>> approvedByTag() {
    Map<String, List<TestCase>> out = new TreeMap<>();
    for (TestCase t : new CaseStore(Workspace.TESTCASES).readAll()) {
      if (!TestCase.APPROVED.equals(t.status())) continue;
      String tag = slug(t.tags() == null || t.tags().isEmpty() ? "default" : t.tags().get(0));
      if (onlyTag == null || onlyTag.equals(tag)) out.computeIfAbsent(tag, k -> new ArrayList<>()).add(t);
    }
    return out;
  }

  public List<AiRequest> requests() throws Exception {
    String framework = framework();
    Map<String, String> failures = heal ? healableFailuresByTag() : Map.of();
    List<AiRequest> out = new ArrayList<>();
    for (Map.Entry<String, List<TestCase>> e : approvedByTag().entrySet()) {
      String tag = e.getKey();
      if (heal && !failures.containsKey(tag)) continue;
      Set<String> specs = new LinkedHashSet<>();
      for (TestCase t : e.getValue()) if (t.source() != null && t.source().spec() != null) specs.add(t.source().spec());
      StringBuilder spec = new StringBuilder();
      for (String s : specs) {
        Path p = Workspace.SPECS.resolve(s);
        spec.append(Files.exists(p) ? SpecText.read(p, 40000) : "(spec " + s + " not in specs/)").append('\n');
      }
      Map<String, String> vars = new LinkedHashMap<>();
      vars.put("tag", tag);
      vars.put("class_prefix", prefix(tag));
      vars.put("cases", AiText.clean(Json.pretty(e.getValue()), 60000));
      vars.put("spec", AiText.clean(spec.toString(), 50000));
      vars.put("existing", existing(tag));
      vars.put("framework", framework);
      if (heal) vars.put("failures", failures.get(tag));
      out.add(new AiRequest(tag, heal ? "heal" : "automation", vars));
    }
    return out;
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    String tag = request.id();
    Map<String, TestCase> approved = new LinkedHashMap<>();
    for (TestCase t : approvedByTag().getOrDefault(tag, List.of())) approved.put(t.id(), t);
    Set<String> approvedIds = approved.keySet();
    Map<Path, String> allowed = Map.of(
        FEATURES.resolve(tag + ".feature"), "feature",
        API.resolve(prefix(tag) + "Api.java"), "com.qa.automation.api.generated",
        STEPS.resolve(prefix(tag) + "Steps.java"), "com.qa.automation.steps.generated");

    JsonNode root = AiText.json(answer);
    JsonNode files = root.path("files");
    String notes = root.path("notes").asText("");
    Map<Path, String> write = new LinkedHashMap<>();
    for (JsonNode f : files) {
      Path target = Workspace.ROOT.resolve(f.path("path").asText("")).normalize();
      // a header from an earlier run is replaced, not stacked
      String content = f.path("content").asText("").replaceAll("(?m)\\A(\\s*(#|//) AI-GENERATED[^\\n]*\\n)+", "");
      String kind = allowed.get(target);
      if (kind == null) throw new IllegalArgumentException("file not allowed for tag " + tag + ": " + f.path("path").asText()
          + " (allowed: " + allowed.keySet().stream().map(Workspace::rel).toList() + ")");
      if (kind.equals("feature")) {
        if (!content.contains("Feature:")) throw new IllegalArgumentException(tag + ".feature has no Feature:");
        Matcher m = TC_TAG.matcher(content);
        while (m.find()) {
          if (!approvedIds.contains(m.group(1))) throw new IllegalArgumentException("scenario tag @" + m.group(1) + " is not an APPROVED case of " + tag);
        }
        checkCoverage(tag, content, approved);
        content = "# AI-GENERATED by " + by + " from the APPROVED cases of tag '" + tag + "' (prompts/" + request.prompt() + ".md). Review before merge.\n" + content.strip() + "\n";
      } else {
        if (!content.contains("package " + kind + ";")) throw new IllegalArgumentException(Workspace.rel(target) + " must declare package " + kind);
        Matcher bad = FORBIDDEN.matcher(content);
        if (bad.find()) throw new IllegalArgumentException(Workspace.rel(target) + " uses " + bad.group() + " (not allowed in generated code)");
        content = "// AI-GENERATED by " + by + " for tag '" + tag + "' (prompts/" + request.prompt() + ".md). Review before merge.\n" + content.strip() + "\n";
      }
      write.put(target, content);
    }
    if (write.isEmpty()) {
      if (heal && !notes.isBlank()) return tag + ": no automation change (" + AiText.clean(notes, 300).strip() + ")";
      throw new IllegalArgumentException("answer has no files");
    }
    for (Map.Entry<Path, String> w : write.entrySet()) {
      Files.createDirectories(w.getKey().getParent());
      Files.writeString(w.getKey(), w.getValue());
    }
    return tag + ": " + write.keySet().stream().map(Workspace::rel).toList();
  }

  public String finish() {
    return heal ? "Healed automation written: re-run the tests (Execute tests, or mvn test -Denv=<env>), then review git diff"
        : "Check: mvn -q test -Dcucumber.execution.dry-run=true   (compiles the code and binds every step), then review git diff";
  }

  /**
   * A feature must keep one scenario per APPROVED case of the tag and state that case's approved expected status
   * in the scenario text, so neither generation nor healing can drop a case or weaken its main expectation.
   */
  static void checkCoverage(String tag, String feature, Map<String, TestCase> approved) {
    Map<String, String> blocks = scenarioBlocks(feature);
    for (TestCase t : approved.values()) {
      String block = blocks.get(t.id());
      if (block == null) throw new IllegalArgumentException(tag + ".feature has no scenario tagged @" + t.id() + " (every APPROVED case needs one)");
      if (t.expected() != null && t.expected().status() > 0 && !block.matches("(?s).*\\b" + t.expected().status() + "\\b.*")) {
        throw new IllegalArgumentException("scenario @" + t.id() + " must state the approved expected status " + t.expected().status());
      }
    }
  }

  /** caseId -> text of its scenario (from its tag line to the next scenario's tags). */
  static Map<String, String> scenarioBlocks(String feature) {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> ids = new ArrayList<>();
    StringBuilder cur = null;
    boolean inScenario = false;
    for (String line : feature.split("\\R")) {
      String t = line.trim();
      if (t.startsWith("@") && (inScenario || cur == null)) {
        if (cur != null) for (String id : ids) out.put(id, cur.toString());
        ids = new ArrayList<>();
        cur = new StringBuilder();
        inScenario = false;
      }
      if (t.startsWith("@")) {
        Matcher m = TC_TAG.matcher(t);
        while (m.find()) ids.add(m.group(1));
      }
      if (t.startsWith("Scenario") || t.startsWith("Example:")) inScenario = true;
      if (cur != null) cur.append(line).append('\n');
    }
    if (cur != null) for (String id : ids) out.put(id, cur.toString());
    return out;
  }

  /** tag -> masked description of its failures that the automation could fix (from rca.json + evidence). */
  private Map<String, String> healableFailuresByTag() throws Exception {
    Map<String, String> tagOf = new LinkedHashMap<>();
    for (Map.Entry<String, List<TestCase>> e : approvedByTag().entrySet()) for (TestCase t : e.getValue()) tagOf.put(t.id(), e.getKey());
    Map<String, com.qa.platform.model.Evidence> evidence = new LinkedHashMap<>();
    for (var ev : com.qa.platform.rca.RcaEngine.failures(Workspace.EVIDENCE)) evidence.put(ev.caseId(), ev);
    Path rcaFile = Workspace.RCA.resolve("rca.json");
    Map<String, StringBuilder> out = new TreeMap<>();
    if (!Files.exists(rcaFile)) return Map.of();
    for (JsonNode entry : Json.MAPPER.readTree(rcaFile.toFile()).path("entries")) {
      String category = entry.path("category").asText("UNCLASSIFIED");
      if (!HEALABLE.contains(category)) continue;
      List<String> ids = new ArrayList<>();
      ids.add(entry.path("caseId").asText());
      entry.path("relatedCaseIds").forEach(r -> ids.add(r.asText()));
      for (String id : ids) {
        String tag = tagOf.get(id);
        if (tag == null || (onlyTag != null && !onlyTag.equals(tag))) continue;
        var ev = evidence.get(id);
        StringBuilder sb = out.computeIfAbsent(tag, k -> new StringBuilder());
        sb.append("- ").append(id).append("  [RCA: ").append(category).append("] ").append(entry.path("summary").asText("")).append('\n');
        if (!entry.path("probableCause").asText("").isBlank()) sb.append("  cause: ").append(entry.path("probableCause").asText()).append('\n');
        if (ev != null) {
          sb.append("  failed step: ").append(ev.failedStep()).append('\n');
          sb.append("  error: ").append(ev.errorType()).append(": ").append(ev.errorMessage()).append('\n');
          if (ev.request() != null) sb.append("  request: ").append(ev.request().method()).append(' ').append(ev.request().url()).append('\n');
          if (ev.response() != null) sb.append("  response: ").append(ev.response().status()).append(' ')
              .append(ev.response().body() == null ? "" : ev.response().body().substring(0, Math.min(600, ev.response().body().length()))).append('\n');
        }
      }
    }
    Map<String, String> masked = new LinkedHashMap<>();
    out.forEach((k, v) -> masked.put(k, AiText.clean(v.toString(), 30000)));
    return masked;
  }

  private static String existing(String tag) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (Path p : List.of(FEATURES.resolve(tag + ".feature"), API.resolve(prefix(tag) + "Api.java"), STEPS.resolve(prefix(tag) + "Steps.java"))) {
      if (Files.exists(p)) sb.append("--- ").append(Workspace.rel(p)).append('\n').append(AiText.clean(Files.readString(p), 20000)).append('\n');
    }
    return sb.toString();
  }

  private static String framework() throws Exception {
    Path base = Workspace.ROOT.resolve("src/test/java/com/qa/automation");
    StringBuilder sb = new StringBuilder();
    for (String f : FRAMEWORK) {
      Path p = base.resolve(f);
      if (Files.exists(p)) sb.append("--- ").append(f).append('\n').append(Files.readString(p)).append('\n');
    }
    return sb.toString();
  }

  static String slug(String tag) { return tag.trim().replaceAll("[^A-Za-z0-9_.-]+", "-"); }

  /** "order-items" -> "OrderItems" */
  static String prefix(String tag) {
    StringBuilder sb = new StringBuilder();
    for (String part : tag.split("[^A-Za-z0-9]+")) {
      if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    String s = sb.length() == 0 ? "Default" : sb.toString();
    return Character.isDigit(s.charAt(0)) ? "T" + s : s;
  }
}
