package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.PromptLibrary;
import com.qa.platform.common.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs an {@link AiTask} with an LLM API or hands it to the IDE agent.
 *
 * <pre>
 * mode llm    each request -> LlmClient -> task.apply            (needs LLM_PROVIDER + token)
 * mode agent  target/ai/&lt;task&gt;/requests.md + manifest.json        (Copilot agent mode / Claude Code answers)
 *             agent writes target/ai/&lt;task&gt;/answers/&lt;id&gt;.&lt;json|yaml|md&gt;
 *             ai &lt;task&gt; --apply --by copilot-agent -> task.apply per answer
 * mode auto   llm when a token is configured, else agent
 * </pre>
 */
public class AiRunner {

  public enum Mode { AUTO, LLM, AGENT;
    public static Mode of(String s) { return s == null || s.isBlank() ? AUTO : valueOf(s.trim().toUpperCase(Locale.ROOT)); }
  }

  public record Result(String task, String mode, int requests, int applied, List<String> messages,
                       List<String> rejected, String requestsFile, String next) {}

  private final Path root;
  private final LlmClient llm;

  public AiRunner() { this(Workspace.ROOT.resolve("target/ai"), new LlmClient(true)); }

  public AiRunner(Path root, LlmClient llm) {
    this.root = root;
    this.llm = llm;
  }

  public boolean llmAvailable() { return llm.configured(); }

  public Result run(AiTask task, Mode mode) throws Exception {
    Mode m = mode == Mode.AUTO ? (llm.configured() ? Mode.LLM : Mode.AGENT) : mode;
    if (m == Mode.LLM && !llm.configured()) {
      throw new IllegalStateException("No LLM token for LLM_PROVIDER=" + llm.provider()
          + ". Configure one (README 'LLM'), or use --mode agent and let Copilot agent mode / Claude Code answer.");
    }
    List<AiTask.AiRequest> requests = task.requests();
    if (requests.isEmpty()) {
      return new Result(task.name(), m.name().toLowerCase(Locale.ROOT), 0, 0, List.of("Nothing to do"), List.of(), null, null);
    }
    return m == Mode.LLM ? viaLlm(task, requests) : export(task, requests);
  }

  private Result viaLlm(AiTask task, List<AiTask.AiRequest> requests) throws Exception {
    List<String> messages = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    int applied = 0;
    for (AiTask.AiRequest r : requests) {
      PromptLibrary.Prompt p = PromptLibrary.load(r.prompt(), r.vars());
      var answer = llm.complete(p.system(), p.user(), task.maxTokens());
      if (answer.isEmpty()) {
        rejected.add(r.id() + ": no answer from " + llm.model() + " (see the [llm] line above)");
        continue;
      }
      try {
        messages.add(task.apply(r, answer.get(), llm.model()));
        applied++;
      } catch (IllegalArgumentException e) {
        rejected.add(r.id() + ": " + e.getMessage());
      }
    }
    String fin = applied > 0 ? task.finish() : null;
    if (fin != null) messages.add(fin);
    return new Result(task.name(), "llm", requests.size(), applied, messages, rejected, null, null);
  }

  private Result export(AiTask task, List<AiTask.AiRequest> requests) throws IOException {
    Path dir = root.resolve(task.name());
    Path answers = dir.resolve("answers");
    deleteTree(dir);
    Files.createDirectories(answers);
    String ext = ext(task.format());

    StringBuilder sb = new StringBuilder();
    sb.append("# AI task: ").append(task.name()).append(" (").append(requests.size()).append(" request")
        .append(requests.size() == 1 ? "" : "s").append(")\n\n");
    sb.append(task.describe()).append("\n\n");
    sb.append("## How to answer (IDE agent)\n\n");
    sb.append("1. For EACH request below, follow the instructions and the request text.\n");
    if ("files".equals(task.format())) {
      sb.append("2. Write each file of your answer as a real file under the answer folder named in the request, keeping its\n")
        .append("   project path: e.g. <answer folder>/src/test/java/com/qa/automation/api/generated/OrdersApi.java.\n")
        .append("   (No JSON needed; optional notes.md in the answer folder for the reviewer.)\n");
    } else {
      sb.append("2. Write ONLY the answer (").append(task.format()).append(") to the answer file named in the request.\n");
    }
    sb.append("3. Then run: `java -jar target/api-test-platform.jar ai ").append(task.name())
        .append(" --apply --by copilot-agent` (or `--by claude-code`). Fix rejected answers and apply again.\n\n");
    sb.append("Answers are validated exactly like LLM answers; nothing changes until --apply. Never write the\n");
    sb.append("target files yourself, and never mark a test case APPROVED.\n\n");

    String system = null;
    Map<String, String> manifestReq = new LinkedHashMap<>();
    StringBuilder body = new StringBuilder();
    for (AiTask.AiRequest r : requests) {
      PromptLibrary.Prompt p = PromptLibrary.load(r.prompt(), r.vars());
      if (system == null) system = p.system();
      Path answerFile = "files".equals(task.format()) ? answers.resolve(AiText.safeId(r.id())) : answers.resolve(AiText.safeId(r.id()) + "." + ext);
      manifestReq.put(r.id(), rel(answerFile));
      body.append("---\n\n## Request ").append(r.id()).append("\n\n");
      body.append("files".equals(task.format()) ? "Answer folder: `" : "Answer file: `").append(manifestReq.get(r.id())).append("`\n\n");
      body.append("````text\n").append(p.user().strip()).append("\n````\n\n");
    }
    sb.append("## Instructions (same for every request)\n\n").append(system == null ? "" : system.strip()).append("\n\n");
    sb.append(body);
    Path requestsFile = dir.resolve("requests.md");
    Files.writeString(requestsFile, sb.toString());

    ObjectNode manifest = Json.MAPPER.createObjectNode();
    manifest.put("task", task.name());
    manifest.put("createdAt", Instant.now().toString());
    manifest.set("args", Json.MAPPER.valueToTree(task.args()));
    manifest.set("requests", Json.MAPPER.valueToTree(manifestReq));
    Json.write(dir.resolve("manifest.json"), manifest);

    String rel = rel(requestsFile);
    return new Result(task.name(), "agent", requests.size(), 0, List.of(), List.of(), rel,
        "Agent: answer " + rel + ", then java -jar target/api-test-platform.jar ai " + task.name() + " --apply --by <agent>");
  }

  /** Applies the answers the agent wrote for the last export of {@code taskName}. */
  public Result applyAnswers(String taskName, String by) throws Exception {
    Path dir = root.resolve(taskName);
    Path manifestFile = dir.resolve("manifest.json");
    if (!Files.exists(manifestFile)) throw new IllegalStateException("Nothing exported for " + taskName + ": run ai " + taskName + " --mode agent first");
    JsonNode manifest = Json.MAPPER.readTree(manifestFile.toFile());
    Map<String, String> args = new LinkedHashMap<>();
    manifest.path("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
    AiTask task = AiTasks.create(taskName, args);
    Map<String, AiTask.AiRequest> current = new LinkedHashMap<>();
    for (AiTask.AiRequest r : task.requests()) current.put(r.id(), r);

    String label = by == null || by.isBlank() ? "ide-agent" : by.replaceAll("[^A-Za-z0-9_.:-]", "");
    List<String> messages = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    int applied = 0;
    var it = manifest.path("requests").fields();
    while (it.hasNext()) {
      var e = it.next();
      String id = e.getKey();
      Path answer = Workspace.ROOT.resolve(e.getValue().asText());
      if (!Files.exists(answer)) {
        rejected.add(id + ": no answer file " + e.getValue().asText());
        continue;
      }
      AiTask.AiRequest r = current.get(id);
      if (r == null) {
        rejected.add(id + ": no longer current (inputs changed since the export): export again");
        continue;
      }
      try {
        String text = Files.isDirectory(answer) ? filesAsJson(answer) : Files.readString(answer);
        messages.add(task.apply(r, text, label));
        applied++;
      } catch (IllegalArgumentException ex) {
        rejected.add(id + ": " + ex.getMessage());
      }
    }
    String fin = applied > 0 ? task.finish() : null;
    if (fin != null) messages.add(fin);
    return new Result(taskName, "agent", manifest.path("requests").size(), applied, messages, rejected, null, null);
  }

  /** Path relative to the project root (answer paths in the manifest stay valid wherever the project lives). */
  private static String rel(Path p) {
    return Workspace.rel(p);
  }

  /** An answer folder (files written by the agent at their project paths) as {"files":[{path, content}]}. */
  private static String filesAsJson(Path dir) throws IOException {
    ObjectNode root = Json.MAPPER.createObjectNode();
    var files = root.putArray("files");
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
        String rel = dir.relativize(f).toString().replace('\\', '/');
        if (rel.equals("notes.md")) { root.put("notes", Files.readString(f)); continue; }
        files.addObject().put("path", rel).put("content", Files.readString(f));
      }
    }
    return Json.MAPPER.writeValueAsString(root);
  }

  private static String ext(String format) {
    return switch (format) { case "json" -> "json"; case "yaml" -> "yaml"; default -> "md"; };
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  public static String format(Result r) {
    StringBuilder sb = new StringBuilder();
    sb.append(r.task()).append(" (").append(r.mode()).append("): ");
    if ("agent".equals(r.mode()) && r.requestsFile() != null) {
      sb.append(r.requests()).append(" request(s) written to ").append(r.requestsFile()).append('\n').append(r.next()).append('\n');
      return sb.toString();
    }
    sb.append(r.applied()).append(" of ").append(r.requests()).append(" applied\n");
    r.messages().forEach(m -> sb.append("  ").append(m).append('\n'));
    r.rejected().forEach(m -> sb.append("  rejected: ").append(m).append('\n'));
    return sb.toString();
  }
}
