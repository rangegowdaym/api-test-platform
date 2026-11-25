package com.qa.platform.cli;

import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.TestCase;
import com.qa.platform.rca.RcaEngine;
import com.qa.platform.report.ReportRcaMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "api-test-platform", mixinStandardHelpOptions = true, version = "1.0.0",
    description = "AI-first API testing: spec -> AI test cases -> HUMAN approval -> AI automation -> run -> AI RCA/heal -> report."
        + " AI tasks are answered by your LLM API when a token is set, else queued for Copilot agent mode (/ai).",
    subcommands = {
        PlatformCli.Generate.class, PlatformCli.Status.class,
        PlatformCli.Propose.class, PlatformCli.Rca.class, PlatformCli.Report.class, PlatformCli.Post.class,
        PlatformCli.Doctor.class, PlatformCli.Ai.class})
public class PlatformCli implements Runnable {

  @Override public void run() { System.out.println("Use --help to list commands, or 'serve' to start the approval UI."); }

  @Command(name = "generate", description = "AI designs the test cases for a spec -> testcases/ (PENDING_REVIEW). Same as: ai testcases")
  public static class Generate implements Callable<Integer> {
    @Option(names = "--spec", required = true, description = "OpenAPI/Swagger YAML/JSON, Postman collection or API doc") String spec;
    @Option(names = "--context", description = "Business rules the spec does not state") String context;
    @Option(names = "--mode", defaultValue = "auto", description = "auto, llm, agent") String mode;

    public Integer call() throws Exception {
      Map<String, String> args = new java.util.LinkedHashMap<>();
      args.put("spec", spec);
      if (context != null) args.put("context", context);
      var r = new com.qa.platform.ai.AiRunner().run(com.qa.platform.ai.AiTasks.create("testcases", args),
          com.qa.platform.ai.AiRunner.Mode.of(mode));
      System.out.print(com.qa.platform.ai.AiRunner.format(r));
      if (!"agent".equals(r.mode())) System.out.println("Next: review them in the approval UI (java -jar target/api-test-platform.jar serve)");
      return r.rejected().isEmpty() ? 0 : 1;
    }
  }

  @Command(name = "status", description = "Count test cases by status and category")
  public static class Status implements Callable<Integer> {
    @Option(names = "--dir") Path dir = Workspace.TESTCASES;

    public Integer call() {
      List<TestCase> all = new CaseStore(dir).readAll();
      Map<String, Long> byStatus = all.stream().collect(Collectors.groupingBy(TestCase::status, TreeMap::new, Collectors.counting()));
      Map<String, Long> byCat = all.stream().collect(Collectors.groupingBy(TestCase::category, TreeMap::new, Collectors.counting()));
      System.out.println("Total: " + all.size());
      System.out.println("By status:   " + byStatus);
      System.out.println("By category: " + byCat);
      try {
        var runner = new com.qa.platform.ai.AiRunner();
        System.out.println("AI answers:  " + runner.llmName());
        var pending = runner.pending();
        pending.forEach(x -> System.out.println("Queued for Copilot: " + x.task() + " " + x.args() + " (" + x.answered() + "/" + x.requests()
            + " answered) -> in Copilot Chat type /ai"));
        System.out.println("Next: " + next(byStatus, pending.isEmpty()));
      } catch (Exception e) {
        System.out.println("AI queue: " + e.getMessage());
      }
      return 0;
    }
  }

  /** The one next step, so a person or an agent always knows what to do. */
  static String next(Map<String, Long> byStatus, boolean queueEmpty) {
    if (!queueEmpty) return "answer the queued AI tasks: Copilot Chat /ai (or set an LLM token and re-run the step)";
    if (byStatus.isEmpty()) return "put a spec in specs/ and run: generate --spec specs/<file>   (or Import spec in the UI)";
    if (byStatus.getOrDefault(TestCase.PENDING, 0L) > 0) return "review the PENDING_REVIEW cases in the approval UI (serve), approve or reject";
    if (byStatus.getOrDefault(TestCase.APPROVED, 0L) > 0 && !Files.exists(Workspace.FEATURES)) return "ai automation (UI button 1)";
    return "ai automation for new approvals, then Execute tests (UI) or scripts/run-tests.sh";
  }

  @Command(name = "propose", description = "Add proposed test cases (e.g. written by hand or by an agent) as PENDING_REVIEW")
  public static class Propose implements Callable<Integer> {
    @Option(names = "--file", required = true, description = "JSON array of test cases, e.g. testcases/_proposed/orders.json")
    Path file;

    public Integer call() throws Exception {
      var r = new com.qa.platform.generate.ProposalImporter(new CaseStore(Workspace.TESTCASES))
          .importCases(Json.MAPPER.readTree(file.toFile()), "agent-proposal", "from " + file.getFileName());
      r.skipped().forEach(x -> System.err.println("SKIP " + x));
      System.out.printf("%d proposed cases added as PENDING_REVIEW, %d skipped%n", r.added(), r.skipped().size());
      return r.skipped().isEmpty() ? 0 : 1;
    }
  }

  @Command(name = "rca", description = "Collect the failures of the last run and let the AI analyse them (LLM now, else queued for Copilot)")
  public static class Rca implements Callable<Integer> {
    @Option(names = "--no-ai", description = "Only collect the facts (failures stay UNCLASSIFIED)") boolean noAi;
    @Option(names = "--llm", hidden = true, description = "Accepted for old scripts; the AI is on by default") boolean llm;

    public Integer call() throws Exception {
      RcaEngine engine = new RcaEngine(Workspace.EVIDENCE, Workspace.ROOT.resolve("target/run-summary.json"),
          Workspace.HISTORY, Workspace.RCA, new CaseStore(Workspace.TESTCASES), noAi ? null : new com.qa.platform.ai.AiRunner());
      RcaEngine.Report r = engine.run();
      System.out.printf("%d failures -> %d root causes (%d new). By category: %s%n",
          r.failures(), r.groups(), r.newFailures(), r.byCategory());
      System.out.println("Written: " + Workspace.RCA.resolve("rca.json"));
      return 0;
    }
  }

  @Command(name = "ai", description = "Run an AI task (testcases, spec-audit, automation, rca, heal): LLM API when a token is set,"
      + " else queued for Copilot agent mode. 'ai pending' lists the queue, 'ai apply' applies Copilot's answers.")
  public static class Ai implements Callable<Integer> {
    @picocli.CommandLine.Parameters(index = "0", description = "Task (${COMPLETION-CANDIDATES}), or: pending, apply",
        completionCandidates = AiTaskNames.class) String task;
    @Option(names = "--spec", description = "testcases, spec-audit: spec or API doc file") String spec;
    @Option(names = "--context", description = "testcases: business rules the spec does not state") String context;
    @Option(names = "--tag", description = "automation, heal: only this OpenAPI tag (default: every tag with APPROVED cases)") String tag;
    @Option(names = "--mode", defaultValue = "auto",
        description = "auto (LLM when a token is configured, else agent), llm, agent") String mode;
    @Option(names = "--apply", description = "Apply the answers the IDE agent wrote for this task") boolean apply;
    @Option(names = "--by", defaultValue = "copilot-agent", description = "Who answered (recorded in the audit trail)") String by;

    public Integer call() throws Exception {
      var runner = new com.qa.platform.ai.AiRunner();
      if ("pending".equals(task)) {
        var pending = runner.pending();
        if (pending.isEmpty()) System.out.println("Nothing queued for the IDE agent.");
        pending.forEach(p -> System.out.println(p.task() + "  " + p.args() + "  answered " + p.answered() + "/" + p.requests()
            + "  -> " + p.requestsFile()));
        return 0;
      }
      if ("apply".equals(task)) {
        var results = runner.applyAll(by);
        if (results.isEmpty()) System.out.println("No answers to apply (ai pending shows what is queued).");
        results.forEach(x -> System.out.print(com.qa.platform.ai.AiRunner.format(x)));
        return results.stream().allMatch(x -> x.rejected().isEmpty()) ? 0 : 1;
      }
      com.qa.platform.ai.AiRunner.Result r;
      if (apply) {
        r = runner.applyAnswers(task, by);
      } else {
        Map<String, String> args = new java.util.LinkedHashMap<>();
        if (spec != null) args.put("spec", spec);
        if (context != null) args.put("context", context);
        if (tag != null) args.put("tag", tag);
        r = runner.run(com.qa.platform.ai.AiTasks.create(task, args), com.qa.platform.ai.AiRunner.Mode.of(mode));
      }
      System.out.print(com.qa.platform.ai.AiRunner.format(r));
      return r.rejected().isEmpty() ? 0 : 1;
    }
  }

  static class AiTaskNames extends java.util.ArrayList<String> {
    AiTaskNames() { super(com.qa.platform.ai.AiTasks.NAMES); }
  }

  @Command(name = "doctor", description = "Check this machine against an environment: config, secrets, proxy, company CA, reachability")
  public static class Doctor implements Callable<Integer> {
    @Option(names = "--env", defaultValue = "qa", description = "config/env/<env>.properties. Default: qa") String env;

    public Integer call() {
      var r = new com.qa.platform.net.EnvironmentDoctor().run(env);
      System.out.print(com.qa.platform.net.EnvironmentDoctor.format(r));
      return r.ok() ? 0 : 1;
    }
  }

  @Command(name = "report", description = "Add target/rca/rca.json to reports/index.html (RCA view + RCA tab per failed test)")
  public static class Report implements Callable<Integer> {
    @Option(names = "--index", description = "Report page written by ModernCucumberReporter. Default: reports/index.html")
    Path index = Workspace.REPORTS.resolve("index.html");

    public Integer call() throws Exception {
      ReportRcaMerger.Result r = new ReportRcaMerger().merge(index, Workspace.RCA.resolve("rca.json"));
      System.out.println(r.message());
      if (Files.exists(index)) System.out.println("Report: " + index.toAbsolutePath().toUri() + "#rca");
      return Files.exists(index) ? 0 : 1;
    }
  }

  @Command(name = "post-run", description = "AI rca + report + one-line summary (used by scripts/run-tests.sh and CI)")
  public static class Post implements Callable<Integer> {
    @Option(names = "--no-ai") boolean noAi;
    @Option(names = "--llm", hidden = true) boolean llm;
    @Option(names = "--heal", description = "After the RCA, let the AI fix automation/test-data failures (then re-run the tests)") boolean heal;

    public Integer call() throws Exception {
      Rca rca = new Rca();
      rca.noAi = noAi;
      rca.call();
      if (heal && !noAi) {
        var r = new com.qa.platform.ai.AiRunner().run(com.qa.platform.ai.AiTasks.create("heal", Map.of()), com.qa.platform.ai.AiRunner.Mode.AUTO);
        System.out.print(com.qa.platform.ai.AiRunner.format(r));
      }
      new Report().call();
      String line = ReportRcaMerger.oneLine(Workspace.ROOT.resolve("target/run-summary.json"), Workspace.RCA.resolve("rca.json"));
      System.out.println("SUMMARY: " + line);
      Json.write(Workspace.ROOT.resolve("target/summary.json"), Map.of("summary", line));
      return 0;
    }
  }
}
