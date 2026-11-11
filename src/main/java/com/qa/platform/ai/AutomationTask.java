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
 * The AI writes the automation for the APPROVED cases of each tag (prompts/automation.md): the feature file,
 * the {@code <Tag>Api} endpoint class and the {@code <Tag>Steps} step definitions, choosing the assertions.
 * Validation before anything is written: fixed paths per tag, Java package matches the folder, every scenario tag
 * {@code @TC-...} is an approved case, no forbidden calls. Testers review the files (git diff) and the dry run.
 */
final class AutomationTask implements AiTask {
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

  AutomationTask(String tag) { this.onlyTag = tag == null || tag.isBlank() ? null : tag; }

  public String name() { return "automation"; }
  public String format() { return "files"; }
  public Map<String, String> args() { return onlyTag == null ? Map.of() : Map.of("tag", onlyTag); }
  public int maxTokens() { return 16000; }
  public String describe() {
    return "Write the feature file, <Tag>Api and <Tag>Steps for the APPROVED cases" + (onlyTag == null ? " of every tag" : " of tag " + onlyTag)
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
    List<AiRequest> out = new ArrayList<>();
    for (Map.Entry<String, List<TestCase>> e : approvedByTag().entrySet()) {
      String tag = e.getKey();
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
      out.add(new AiRequest(tag, "automation", vars));
    }
    return out;
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    String tag = request.id();
    Set<String> approvedIds = new LinkedHashSet<>();
    for (TestCase t : approvedByTag().getOrDefault(tag, List.of())) approvedIds.add(t.id());
    Map<Path, String> allowed = Map.of(
        FEATURES.resolve(tag + ".feature"), "feature",
        API.resolve(prefix(tag) + "Api.java"), "com.qa.automation.api.generated",
        STEPS.resolve(prefix(tag) + "Steps.java"), "com.qa.automation.steps.generated");

    JsonNode files = AiText.json(answer).path("files");
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
        content = "# AI-GENERATED by " + by + " from the APPROVED cases of tag '" + tag + "' (prompts/automation.md). Review before merge.\n" + content.strip() + "\n";
      } else {
        if (!content.contains("package " + kind + ";")) throw new IllegalArgumentException(Workspace.rel(target) + " must declare package " + kind);
        Matcher bad = FORBIDDEN.matcher(content);
        if (bad.find()) throw new IllegalArgumentException(Workspace.rel(target) + " uses " + bad.group() + " (not allowed in generated code)");
        content = "// AI-GENERATED by " + by + " for tag '" + tag + "' (prompts/automation.md). Review before merge.\n" + content.strip() + "\n";
      }
      write.put(target, content);
    }
    if (write.isEmpty()) throw new IllegalArgumentException("answer has no files");
    for (Map.Entry<Path, String> w : write.entrySet()) {
      Files.createDirectories(w.getKey().getParent());
      Files.writeString(w.getKey(), w.getValue());
    }
    return tag + ": " + write.keySet().stream().map(Workspace::rel).toList();
  }

  public String finish() {
    return "Check: mvn -q test -Dcucumber.execution.dry-run=true   (compiles the code and binds every step), then review git diff";
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
