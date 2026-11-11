package com.qa.platform.approval;

import com.qa.platform.ai.AiRunner;
import com.qa.platform.ai.AiTasks;
import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.rca.RcaEngine;
import com.qa.platform.report.ReportRcaMerger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "Execute tests" in the approval UI: runs {@code mvn test} for the chosen environment and tag expression
 * in the project folder, streams its output to the page, then lets the AI do the RCA and adds it to the report.
 * With "heal" the AI then fixes automation/test-data failures (never expectations) and the run is repeated once.
 * Without an LLM token the AI steps are queued for Copilot agent mode (/ai).
 *
 * <p>Safety: the command is a fixed argument list started without a shell; environment and tags are
 * validated against strict patterns; one run at a time; only REVIEWER users can start or cancel a run
 * (see AppConfig). Secrets come from the server's environment variables, never from the request.
 */
@Service
public class TestRunService {

  private static final Logger LOG = LoggerFactory.getLogger(TestRunService.class);
  static final Pattern ENV = Pattern.compile("[A-Za-z0-9_-]{1,40}");
  /** Cucumber tag expressions: @tags, and/or/not, parentheses. Nothing a shell or Maven could interpret. */
  static final Pattern TAGS = Pattern.compile("[A-Za-z0-9@_.\\-()\\s]{0,500}");
  private static final int MAX_LINES = 5000;

  public enum Status { RUNNING, PASSED, FAILED, ERROR, CANCELLED }

  public record Run(String id, String env, String tags, int threads, boolean ai, String startedBy, String startedAt,
                    Status status, Integer exitCode, String finishedAt, String summary, String reportUrl,
                    String phase, int lines) {}

  public record Log(Run run, int from, List<String> lines) {}

  /** ai: AI root cause analysis (default on); heal: AI fixes automation failures and re-runs once (needs an LLM token). */
  public record StartRequest(String env, String tags, Integer threads, Boolean ai, Boolean heal) {}

  private final PipelineService pipeline;
  private final String mvn;
  private final String reportUrl;
  private final AtomicReference<Run> current = new AtomicReference<>();
  private final List<String> lines = new ArrayList<>();
  private volatile Process process;
  private volatile boolean cancelled;
  private volatile boolean heal;
  private List<String> command = List.of();

  private final List<String> mvnArgs;

  public TestRunService(PipelineService pipeline, @Value("${platform.mvn:}") String mvn,
                        @Value("${platform.mvn-args:}") String mvnArgs, PlatformProperties props) {
    this.pipeline = pipeline;
    // server-side config only (never from the request): e.g. "-s config/maven/settings.xml" for the company repository
    this.mvnArgs = mvnArgs == null || mvnArgs.isBlank() ? List.of() : List.of(mvnArgs.trim().split("\\s+"));
    this.mvn = mvn == null || mvn.isBlank() ? defaultMaven() : mvn;
    this.reportUrl = props.reportUrl() == null ? "/reports/index.html" : props.reportUrl();
  }

  public synchronized Run start(StartRequest r, String actor) throws IOException {
    Run running = current.get();
    if (running != null && running.status() == Status.RUNNING) {
      throw new IllegalStateException("A run is already in progress (started by " + running.startedBy() + ")");
    }
    String env = r.env() == null || r.env().isBlank() ? "qa" : r.env().trim();
    if (!ENV.matcher(env).matches() || !pipeline.environments().contains(env)) {
      throw new IllegalArgumentException("Unknown environment '" + env + "': add config/env/" + env + ".properties");
    }
    String tags = r.tags() == null ? "" : r.tags().trim().replaceAll("\\s+", " ");
    if (!TAGS.matcher(tags).matches()) {
      throw new IllegalArgumentException("Tag expression may only contain @tags, and, or, not and parentheses");
    }
    int threads = r.threads() == null ? 4 : Math.max(1, Math.min(16, r.threads()));
    boolean ai = !Boolean.FALSE.equals(r.ai());
    heal = ai && Boolean.TRUE.equals(r.heal());

    List<String> cmd = new ArrayList<>(List.of(mvn, "-B"));
    cmd.addAll(mvnArgs);
    cmd.addAll(List.of("test", "-Denv=" + env, "-Dthreads=" + threads));
    if (!tags.isEmpty()) cmd.add("-Dcucumber.filter.tags=" + tags);

    synchronized (lines) { lines.clear(); }
    cancelled = false;
    String id = "run-" + Instant.now().toEpochMilli();
    Run run = new Run(id, env, tags.isEmpty() ? "(all)" : tags, threads, ai, actor, Instant.now().toString(),
        Status.RUNNING, null, null, null, reportUrl, "mvn test", 0);
    current.set(run);
    command = cmd;
    Thread t = new Thread(() -> pipeline(run), "test-run-" + id);
    t.setDaemon(true);
    t.start();
    return current.get();
  }

  /** mvn test -> AI RCA -> report; with heal: AI fixes the automation -> mvn test again -> AI RCA -> report. */
  private void pipeline(Run run) {
    Integer exit = mavenTest();
    if (exit == null && !cancelled) return;                      // could not start Maven (finish() already called)
    String summary = afterRun(run);
    if (heal && !cancelled && failedCount(summary) > 0 && healed()) {
      phase("re-run after heal");
      append("[platform] re-running the same tests with the healed automation");
      exit = mavenTest();
      if (exit == null && !cancelled) return;
      summary = afterRun(run);
    }
    if (cancelled) { finish(Status.CANCELLED, exit, "Cancelled"); return; }
    if ((exit == null || exit != 0) && !Files.exists(Workspace.ROOT.resolve("target/run-summary.json"))) {
      finish(Status.ERROR, exit, "Build failed before any test ran (compile error? missing step classes?): see the log");
      return;
    }
    append("[platform] SUMMARY: " + summary);
    Status status = exit == null || exit != 0 ? Status.ERROR : failedCount(summary) > 0 ? Status.FAILED : Status.PASSED;
    finish(status, exit, summary);
  }

  /** Runs the AI heal task now (LLM only: a queued heal is picked up by Copilot's /ai). True when files changed. */
  private boolean healed() {
    phase("AI heal");
    try {
      AiRunner runner = new AiRunner();
      if (!runner.llmAvailable()) {
        append("[platform] heal needs an LLM token on the server; in Copilot Chat type /ai (it heals after the RCA)");
        return false;
      }
      AiRunner.Result r = runner.run(AiTasks.create("heal", Map.of()), AiRunner.Mode.LLM);
      r.messages().forEach(m -> append("[platform] heal: " + m));
      r.rejected().forEach(m -> append("[platform] heal rejected: " + m));
      return r.messages().stream().anyMatch(m -> m.contains("src/test/"));
    } catch (Exception e) {
      append("[platform] heal failed: " + e.getMessage());
      return false;
    }
  }

  /** Runs mvn test, streaming the log. Null when Maven could not be started (the run is finished as ERROR). */
  private Integer mavenTest() {
    phase("mvn test");
    append("$ " + String.join(" ", command.stream().map(TestRunService::quote).toList()));
    ProcessBuilder pb = new ProcessBuilder(command).directory(Workspace.ROOT.toFile()).redirectErrorStream(true);
    try {
      // a failed build must not be analysed with the previous run's summary
      Files.deleteIfExists(Workspace.ROOT.resolve("target/run-summary.json"));
      process = pb.start();
    } catch (IOException e) {
      finish(Status.ERROR, null, "Could not start '" + mvn + "': " + e.getMessage()
          + ". Install Maven, or set PLATFORM_MVN to its full path (e.g. C:\\tools\\maven\\bin\\mvn.cmd).");
      return null;
    }
    Path logFile = Workspace.OUT.resolve("last-run.log");
    Integer exit = null;
    try {
      Files.createDirectories(logFile.getParent());
      try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
           Writer out = Files.newBufferedWriter(logFile)) {
        String line;
        while ((line = in.readLine()) != null) {
          String clean = Masking.text(line.replaceAll("\u001B\\[[;\\d]*m", ""));
          append(clean);
          out.write(clean);
          out.write('\n');
        }
      }
      exit = process.waitFor();
    } catch (IOException e) {
      append("[platform] log stream closed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return exit == null ? -1 : exit;
  }

  /** RCA (AI answers now, or queued for Copilot) + report + one-line summary. */
  private String afterRun(Run run) {
    // maven.test.failure.ignore=true in the pom: failing tests still exit 0; a build/compile error leaves no summary
    if (cancelled || !Files.exists(Workspace.ROOT.resolve("target/run-summary.json"))) return "no test results";
    phase("AI RCA");
    String summary;
    try {
      RcaEngine engine = new RcaEngine(Workspace.EVIDENCE, Workspace.ROOT.resolve("target/run-summary.json"),
          Workspace.HISTORY, Workspace.RCA, new CaseStore(Workspace.TESTCASES), run.ai() ? new AiRunner() : null);
      RcaEngine.Report rca = engine.run();
      AiRunner.Result ai = engine.aiResult();
      append("[platform] RCA: " + rca.failures() + " failures -> " + rca.groups() + " root causes (" + rca.newFailures() + " new)"
          + (ai == null ? "" : "llm".equals(ai.mode()) ? " [analysed by AI]" : " [queued for Copilot]"));
      if (ai != null && "agent".equals(ai.mode())) append("[platform] " + ai.next());
      phase("report");
      ReportRcaMerger.Result merged = new ReportRcaMerger().merge(Workspace.REPORTS.resolve("index.html"), Workspace.RCA.resolve("rca.json"));
      append("[platform] " + merged.message());
      summary = ReportRcaMerger.oneLine(Workspace.ROOT.resolve("target/run-summary.json"), Workspace.RCA.resolve("rca.json"));
      Json.write(Workspace.ROOT.resolve("target/summary.json"), Map.of("summary", summary));
    } catch (Exception e) {
      LOG.warn("Post-run step failed", e);
      append("[platform] post-run failed: " + e.getMessage());
      summary = "post-run failed: " + e.getMessage();
    }
    return summary;
  }

  private static long failedCount(String summary) {
    var m = Pattern.compile("(\\d+) failed").matcher(summary);
    return m.find() ? Long.parseLong(m.group(1)) : 0;
  }

  public synchronized Run cancel() {
    Run run = current.get();
    if (run == null || run.status() != Status.RUNNING || process == null) return run;
    cancelled = true;
    append("[platform] cancelling...");
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    return current.get();
  }

  public Run current() {
    Run run = current.get();
    if (run == null) return null;
    synchronized (lines) { return withLines(run, lines.size()); }
  }

  /** Log lines from index {@code from} (the page polls with the count it already has). */
  public Log log(int from) {
    Run run = current();
    synchronized (lines) {
      int start = Math.max(0, Math.min(from, lines.size()));
      return new Log(run, start, List.copyOf(lines.subList(start, lines.size())));
    }
  }

  private void append(String line) {
    synchronized (lines) {
      if (lines.size() >= MAX_LINES) lines.subList(0, 500).clear();   // keep memory bounded; full log is in last-run.log
      lines.add(line);
    }
  }

  private void phase(String phase) {
    current.updateAndGet(r -> new Run(r.id(), r.env(), r.tags(), r.threads(), r.ai(), r.startedBy(), r.startedAt(),
        r.status(), r.exitCode(), r.finishedAt(), r.summary(), r.reportUrl(), phase, r.lines()));
  }

  private void finish(Status status, Integer exit, String summary) {
    append("[platform] " + status.name().toLowerCase(Locale.ROOT) + (exit == null ? "" : " (exit " + exit + ")"));
    current.updateAndGet(r -> new Run(r.id(), r.env(), r.tags(), r.threads(), r.ai(), r.startedBy(), r.startedAt(),
        status, exit, Instant.now().toString(), summary, r.reportUrl(), "done", r.lines()));
    process = null;
  }

  private static Run withLines(Run r, int n) {
    return new Run(r.id(), r.env(), r.tags(), r.threads(), r.ai(), r.startedBy(), r.startedAt(), r.status(), r.exitCode(),
        r.finishedAt(), r.summary(), r.reportUrl(), r.phase(), n);
  }

  private static String defaultMaven() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    Path wrapper = Workspace.ROOT.resolve(windows ? "mvnw.cmd" : "mvnw");
    if (Files.isExecutable(wrapper)) return wrapper.toString();
    return windows ? "mvn.cmd" : "mvn";
  }

  private static String quote(String arg) { return arg.contains(" ") ? "\"" + arg + "\"" : arg; }
}
