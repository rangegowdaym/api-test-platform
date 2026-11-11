package com.qa.platform.approval;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
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
 * POST /api/automation/generate AI: features + &lt;Tag&gt;Api + &lt;Tag&gt;Steps for the APPROVED cases
 * GET  /api/ai/options          who answers (LLM or Copilot), queued tasks, specs
 * POST /api/ai/{task}           run an AI task (answered now with a token, else queued for Copilot)
 * POST /api/ai/apply            apply answers Copilot wrote (same as "ai apply")
 * GET  /api/run/options         environments + feature tags for the Execute dialog
 * POST /api/runs                Execute tests  {env, tags, threads, ai, heal}
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
  private final AiService ai;

  public PipelineController(PipelineService pipeline, TestRunService runs, AiService ai) {
    this.pipeline = pipeline;
    this.runs = runs;
    this.ai = ai;
  }

  /** AI writes feature files, <Tag>Api and <Tag>Steps for the APPROVED cases (all tags, or ?tag=orders). */
  @PostMapping("/automation/generate")
  public com.qa.platform.ai.AiRunner.Result automation(@RequestParam(required = false) String tag) throws Exception {
    return pipeline.generateAutomation(tag);
  }

  @GetMapping("/run/options")
  public Map<String, Object> options() throws Exception {
    Map<String, Object> out = new LinkedHashMap<>(pipeline.options());
    out.put("current", runs.current());
    out.put("llmAvailable", new com.qa.platform.ai.AiRunner().llmAvailable());
    return out;
  }

  @PostMapping("/runs")
  public TestRunService.Run start(@RequestBody TestRunService.StartRequest r, Principal p) throws Exception {
    return runs.start(r, p.getName());
  }

  @GetMapping("/runs/current")
  public TestRunService.Log current(@RequestParam(defaultValue = "0") int from) { return runs.log(from); }

  /** Who answers AI tasks (LLM API or Copilot), the tasks queued for Copilot, and the specs the tasks can use. */
  @GetMapping("/ai/options")
  public Map<String, Object> aiOptions() throws Exception {
    Map<String, Object> out = new LinkedHashMap<>(ai.status());
    out.put("specs", pipeline.specs().stream().map(p -> com.qa.platform.common.Workspace.rel(java.nio.file.Path.of(p))).toList());
    return out;
  }

  /** POST /api/ai/{task} {spec?, context?, tag?}: answered now with the server's LLM token, else queued for Copilot. */
  @PostMapping("/ai/{task}")
  public com.qa.platform.ai.AiRunner.Result ai(@org.springframework.web.bind.annotation.PathVariable String task,
                                               @RequestBody(required = false) Map<String, String> body) throws Exception {
    if (!com.qa.platform.ai.AiTasks.NAMES.contains(task)) throw new IllegalArgumentException("Unknown AI task: " + task);
    Map<String, String> args = new LinkedHashMap<>();
    String spec = body == null ? null : body.get("spec");
    if (spec != null && !spec.isBlank()) {
      // only specs the platform already knows (specs/ or the cases' sources), never an arbitrary path
      String known = pipeline.specs().stream().filter(p -> p.endsWith(spec) || p.equals(spec)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown spec: " + spec));
      args.put("spec", com.qa.platform.common.Workspace.rel(java.nio.file.Path.of(known)));
    }
    String tag = body == null ? null : body.get("tag");
    if (tag != null && !tag.isBlank()) {
      if (!tag.matches("[A-Za-z0-9_.-]{1,60}")) throw new IllegalArgumentException("Bad tag: " + tag);
      args.put("tag", tag);
    }
    if (body != null && body.get("context") != null) args.put("context", body.get("context").substring(0, Math.min(4000, body.get("context").length())));
    return ai.run(task, args);
  }

  @PostMapping("/ai/apply")
  public List<com.qa.platform.ai.AiRunner.Result> applyAi(Principal p) throws Exception { return ai.applyAll(p.getName()); }

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
