package com.qa.automation.hooks;

import com.qa.automation.config.TestConfig;
import com.qa.automation.context.ScenarioContext;
import com.qa.automation.support.CurlBuilder;
import com.qa.automation.support.ErrorRegistry;
import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import com.qa.platform.common.Workspace;
import com.qa.platform.model.Evidence;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.rg.reporter.ReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-data attachments for every scenario (After order 110, runs first), evidence for RCA (order 100)
 * and test-data cleanup (After order 10, runs last). Cucumber runs @After hooks from the highest order to the lowest.
 */
public class Hooks {

  private static final Logger LOG = LoggerFactory.getLogger(Hooks.class);
  private static final AtomicBoolean REPORT_INIT = new AtomicBoolean();
  private final ScenarioContext ctx;
  private long start;

  public Hooks(ScenarioContext ctx) { this.ctx = ctx; }

  @Before(order = 0)
  public void before(Scenario scenario) {
    start = System.currentTimeMillis();
    reportContext();
  }

  /** Title, project (= environment) and Environment card entries of reports/index.html (ModernCucumberReporter). */
  private static void reportContext() {
    TestConfig cfg = TestConfig.get();
    ReportContext.project(cfg.env(), "api");
    if (REPORT_INIT.compareAndSet(false, true)) {
      ReportContext.title(System.getProperty("report.title", "API Test Report"));
      // step locations should point at the Api class / step that made the call, not at the client internals
      ReportContext.skipFramesFrom("com.qa.automation.client.");
      ReportContext.skipFramesFrom("com.qa.automation.assertions.");
      ReportContext.environment("Environment", cfg.env());
      ReportContext.environment("Base URI", cfg.get("baseUri", "(per service: baseUri.<tag>)"));
      ReportContext.environment("Tags", System.getProperty("cucumber.filter.tags", "(all)"));
      ReportContext.environment("Threads", System.getProperty("threads", "1"));
    }
  }

  /** Attaches every HTTP exchange (request/response with headers, parameters and bodies) to every scenario. */
  @After(order = 110)
  public void attachTestData(Scenario scenario) {
    List<ScenarioContext.Exchange> exchanges = ctx.exchanges();
    int i = 0;
    for (ScenarioContext.Exchange ex : exchanges) {
      i++;
      String name = (exchanges.size() > 1 ? "call-" + i + " " : "") + label(ex.request());
      // already masked by CaptureFilter, masked again to be safe
      scenario.attach(Masking.text(Json.pretty(ex)), "application/json", name + ".json");
    }
  }

  /** "POST /user/login" from the recorded request (falls back to the full URL). */
  private static String label(Evidence.HttpRequest req) {
    if (req == null) return "http-exchange";
    String path = req.url();
    try {
      String p = java.net.URI.create(req.url()).getPath();
      if (p != null && !p.isBlank()) path = p;
    } catch (IllegalArgumentException ignored) {
      // keep the full URL
    }
    return req.method() + " " + path;
  }

  /** target/evidence/<caseId>.json for every failed scenario (all scenarios with -Devidence.all=true). */
  @After(order = 100)
  public void evidence(Scenario scenario) {
    boolean failed = scenario.isFailed();
    if (!failed && !Boolean.getBoolean("evidence.all")) return;

    ErrorRegistry.Failure f = ErrorRegistry.take(scenario.getId());
    Throwable t = f == null ? null : f.error();
    String caseId = scenario.getSourceTagNames().stream().filter(s -> s.startsWith("@TC-")).map(s -> s.substring(1))
        .findFirst().orElse(scenario.getName().replaceAll("[^A-Za-z0-9]+", "_"));

    Evidence e = new Evidence(
        caseId, scenario.getName(), scenario.getUri().toString(), List.copyOf(scenario.getSourceTagNames()),
        TestConfig.get().env(), failed ? "FAILED" : "PASSED",
        start, System.currentTimeMillis() - start, ErrorRegistry.retries(scenario.getName()),
        ctx.lastRequest(), ctx.lastResponse(),
        f == null ? null : f.step(),
        t == null ? null : t.getClass().getName(),
        t == null ? null : Masking.text(t.getMessage()),
        t == null ? null : stack(t),
        ctx.testCase == null ? null : ctx.testCase.expected().status(),
        ctx.endpoint,
        CurlBuilder.of(ctx.lastRequest()));
    Json.write(Workspace.EVIDENCE.resolve(caseId + ".json"), e);

    if (failed) {
      // shown in the report's Attachments tab; already masked by CaptureFilter, masked again to be safe
      scenario.attach(Masking.text(Json.pretty(e)), "application/json", "evidence.json");
      if (e.curl() != null) scenario.attach(Masking.text(e.curl()), "text/plain", "reproduce.curl");
    }
  }

  /** Undo test data created through services (newest first). A failing cleanup is logged, never fails the test. */
  @After(order = 10)
  public void cleanup() {
    while (!ctx.cleanups().isEmpty()) {
      ScenarioContext.Cleanup c = ctx.cleanups().pop();
      try {
        c.action().run();
      } catch (RuntimeException ex) {
        LOG.warn("Cleanup '{}' failed: {}", c.name(), ex.getMessage());
      }
    }
  }

  private static String stack(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    String s = sw.toString();
    return s.length() > 6000 ? s.substring(0, 6000) + "\n..." : s;
  }
}
