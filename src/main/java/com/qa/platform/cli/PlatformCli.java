package com.qa.platform.cli;

import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
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
    description = "Swagger/API docs -> JSON test cases -> human approval -> Cucumber features -> RCA report",
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
      return 0;
    }
  }

  @Command(name = "propose", description = "Add proposed test cases (e.g. written by the test-designer agent) as PENDING_REVIEW")
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

  @Command(name = "rca", description = "Collect the failures of the last run -> target/rca/rca.json; with --llm the AI analyses them")
  public static class Rca implements Callable<Integer> {
    @Option(names = "--llm", description = "Let the LLM API do the RCA now: category, owner, grouping, fix, tickets, triage (LLM_PROVIDER, README 6a)") boolean llm;

    public Integer call() throws Exception {
      if (llm) System.setProperty("llm.enabled", "true");
      LlmClient client = new LlmClient();
      RcaEngine.Report r = new RcaEngine(Workspace.EVIDENCE, Workspace.ROOT.resolve("target/run-summary.json"),
          Workspace.HISTORY, Workspace.RCA, new CaseStore(Workspace.TESTCASES), client).run();
      System.out.printf("%d failures -> %d root causes (%d new). By category: %s%n",
          r.failures(), r.groups(), r.newFailures(), r.byCategory());
      System.out.println("Written: " + Workspace.RCA.resolve("rca.json"));
      if (r.groups() > 0 && (!llm || !client.enabled())) {
        if (llm) System.out.println("No token for LLM_PROVIDER=" + client.provider() + ": failures stay UNCLASSIFIED for now.");
        System.out.println("AI root cause analysis from your IDE agent (no token needed): run /rca in Copilot agent mode,"
            + " or: java -jar target/api-test-platform.jar ai rca --mode agent");
      }
      return 0;
    }
  }

  @Command(name = "ai", description = "AI-assisted tasks, by LLM API or by your IDE agent: "
      + "testcases, spec-audit, automation (features + Api + Steps), rca")
  public static class Ai implements Callable<Integer> {
    @picocli.CommandLine.Parameters(index = "0", description = "Task: ${COMPLETION-CANDIDATES}",
        completionCandidates = AiTaskNames.class) String task;
    @Option(names = "--spec", description = "testcases, spec-audit: spec or API doc file") String spec;
    @Option(names = "--context", description = "testcases: business rules the spec does not state") String context;
    @Option(names = "--tag", description = "automation: only this OpenAPI tag (default: every tag with APPROVED cases)") String tag;
    @Option(names = "--mode", defaultValue = "auto",
        description = "auto (LLM when a token is configured, else agent), llm, agent") String mode;
    @Option(names = "--apply", description = "Apply the answers the IDE agent wrote for the last --mode agent export") boolean apply;
    @Option(names = "--by", defaultValue = "ide-agent", description = "With --apply: who answered, e.g. copilot-agent, claude-code") String by;

    public Integer call() throws Exception {
      var runner = new com.qa.platform.ai.AiRunner();
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

  @Command(name = "post-run", description = "rca + report + one-line summary (used by scripts/run-tests.sh and CI)")
  public static class Post implements Callable<Integer> {
    @Option(names = "--llm") boolean llm;

    public Integer call() throws Exception {
      Rca rca = new Rca();
      rca.llm = llm;
      rca.call();
      new Report().call();
      String line = ReportRcaMerger.oneLine(Workspace.ROOT.resolve("target/run-summary.json"), Workspace.RCA.resolve("rca.json"));
      System.out.println("SUMMARY: " + line);
      Json.write(Workspace.ROOT.resolve("target/summary.json"), Map.of("summary", line));
      return 0;
    }
  }
}
