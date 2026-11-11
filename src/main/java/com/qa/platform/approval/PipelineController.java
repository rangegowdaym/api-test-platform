package com.qa.platform.approval;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pipeline buttons of the approval UI. POST endpoints need the REVIEWER role (AppConfig); GET works for VIEWER too.
 *
 * <pre>
 * POST /api/features/generate   Generate features            (APPROVED cases only)
 * POST /api/services/generate   Generate services &amp; steps   (&lt;Tag&gt;Api + &lt;Tag&gt;Steps)
 * GET  /api/run/options         environments + feature tags for the Execute dialog
 * POST /api/runs                Execute tests  {env, tags, threads, llm}
 * GET  /api/runs/current?from=N run status + log lines from N
 * POST /api/runs/cancel
 * GET  /api/doctor?env=qa       environment check (config, secrets set, proxy, company CA, reachability)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

  private final PipelineService pipeline;
  private final TestRunService runs;

  public PipelineController(PipelineService pipeline, TestRunService runs) {
    this.pipeline = pipeline;
    this.runs = runs;
  }

  @PostMapping("/features/generate")
  public PipelineService.FeaturesResult features() throws Exception { return pipeline.generateFeatures(); }

  @PostMapping("/services/generate")
  public PipelineService.CodegenResult services() throws Exception { return pipeline.generateServices(); }

  @GetMapping("/run/options")
  public Map<String, Object> options() throws Exception {
    Map<String, Object> out = new LinkedHashMap<>(pipeline.options());
    out.put("current", runs.current());
    // the LLM checkbox is only offered when a token for the configured provider is present on the server
    out.put("llmAvailable", new com.qa.platform.common.LlmClient().configured());
    return out;
  }

  @PostMapping("/runs")
  public TestRunService.Run start(@RequestBody TestRunService.StartRequest r, Principal p) throws Exception {
    return runs.start(r, p.getName());
  }

  @GetMapping("/runs/current")
  public TestRunService.Log current(@RequestParam(defaultValue = "0") int from) { return runs.log(from); }

  /** AI assist: what is available, and the specs the tasks can work on. */
  @GetMapping("/ai/options")
  public Map<String, Object> aiOptions() throws Exception {
    var llm = new com.qa.platform.common.LlmClient(true);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("llmAvailable", llm.configured());
    out.put("provider", llm.model());
    out.put("tasks", com.qa.platform.ai.AiTasks.NAMES);
    out.put("specs", pipeline.specs().stream().map(p -> com.qa.platform.common.Workspace.ROOT.toAbsolutePath().normalize()
        .relativize(java.nio.file.Path.of(p).toAbsolutePath().normalize()).toString()).toList());
    return out;
  }

  /**
   * POST /api/ai/{task} {spec?, context?}: runs the task with the server's LLM. The browser has no IDE agent, so
   * without a token the answer is 409 with the agent-mode command to use in VS Code instead.
   */
  @PostMapping("/ai/{task}")
  public com.qa.platform.ai.AiRunner.Result ai(@org.springframework.web.bind.annotation.PathVariable String task,
                                               @RequestBody(required = false) Map<String, String> body) throws Exception {
    if (!com.qa.platform.ai.AiTasks.NAMES.contains(task) || "doc-to-openapi".equals(task)) {
      throw new IllegalArgumentException("Unknown AI task for the UI: " + task);
    }
    Map<String, String> args = new LinkedHashMap<>();
    String spec = body == null ? null : body.get("spec");
    if (spec != null && !spec.isBlank()) {
      // only specs the platform already knows (specs/ or the cases' sources), never an arbitrary path
      boolean known = pipeline.specs().stream().anyMatch(p -> p.endsWith(spec) || p.equals(spec));
      if (!known) throw new IllegalArgumentException("Unknown spec: " + spec);
      args.put("spec", pipeline.specs().stream().filter(p -> p.endsWith(spec) || p.equals(spec)).findFirst().orElseThrow());
    }
    if (body != null && body.get("context") != null) args.put("context", body.get("context").substring(0, Math.min(4000, body.get("context").length())));
    var runner = new com.qa.platform.ai.AiRunner();
    if (!runner.llmAvailable()) {
      throw new IllegalStateException("No LLM token on the server. In VS Code Copilot agent mode run: "
          + "java -jar target/api-test-platform.jar ai " + task + (args.containsKey("spec") ? " --spec " + spec : "") + " --mode agent");
    }
    synchronized (this) {
      return runner.run(com.qa.platform.ai.AiTasks.create(task, args), com.qa.platform.ai.AiRunner.Mode.LLM);
    }
  }

  @GetMapping("/doctor")
  public com.qa.platform.net.EnvironmentDoctor.Report doctor(@RequestParam(defaultValue = "qa") String env) throws Exception {
    if (!TestRunService.ENV.matcher(env).matches() || !pipeline.environments().contains(env)) {
      throw new IllegalArgumentException("Unknown environment '" + env + "'");
    }
    return new com.qa.platform.net.EnvironmentDoctor().run(env);
  }

  @PostMapping("/runs/cancel")
  public TestRunService.Run cancel() { return runs.cancel(); }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
    return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
