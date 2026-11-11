package com.qa.platform.cli;

import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.Workspace;
import com.qa.platform.feature.FeatureWriter;
import com.qa.platform.generate.CaseDiffer;
import com.qa.platform.generate.CaseGenerator;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.ingest.IngestService;
import com.qa.platform.model.ApiModel;
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
        PlatformCli.Ingest.class, PlatformCli.Generate.class, PlatformCli.Status.class,
        PlatformCli.Propose.class, PlatformCli.Services.class, PlatformCli.Features.class, PlatformCli.Rca.class, PlatformCli.Report.class, PlatformCli.Post.class,
        PlatformCli.Doctor.class, PlatformCli.Ai.class})
public class PlatformCli implements Runnable {

  @Override public void run() { System.out.println("Use --help to list commands, or 'serve' to start the approval UI."); }

  @Command(name = "ingest", description = "Parse a spec/doc and print what was found (writes target/platform/ingest-report.json)")
  public static class Ingest implements Callable<Integer> {
    @Option(names = "--spec", required = true, description = "Swagger/OpenAPI YAML|JSON, Postman collection, Markdown/HTML doc; file or URL")
    String spec;
    @Option(names = "--env", defaultValue = "qa", description = "Config to check: config/env/<env>.properties") String env;

    /** Tells the user exactly which config keys this spec needs and which are still missing. */
    static void printConfigChecklist(ApiModel m, String env) throws java.io.IOException {
      Path file = Workspace.CONFIG.resolve("env").resolve(env + ".properties");
      java.util.Properties props = new java.util.Properties();
      if (java.nio.file.Files.exists(file)) try (var in = java.nio.file.Files.newInputStream(file)) { props.load(in); }

      java.util.Set<String> needed = new java.util.TreeSet<>();
      boolean secured = false;
      for (var e : m.endpoints()) {
        e.params("path").forEach(p -> needed.add("testdata." + p.name()));
        secured |= e.secured();
      }
      if (!props.containsKey("baseUri") || props.getProperty("baseUri").contains("example.com")) needed.add("baseUri");
      if (secured) needed.add("auth.default.type");

      java.util.List<String> missing = needed.stream()
          .filter(k -> !props.containsKey(k) || props.getProperty(k).isBlank() || props.getProperty(k).contains("example.com"))
          .toList();
      System.out.println();
      System.out.println("Config check (" + file + "):");
      if (missing.isEmpty()) {
        System.out.println("  OK: base URI, auth and every testdata.* id are set.");
      } else {
        System.out.println("  Set these before running tests" + (m.servers().isEmpty() ? "" : " (spec server: " + m.servers().get(0) + ")") + ":");
        missing.forEach(k -> System.out.println("    " + k + "="));
      }
      if (secured) System.out.println("  Secured endpoints found: export the token/secret your auth.default.* lines reference.");
    }

    public Integer call() throws Exception {
      ApiModel m = new IngestService().ingest(spec, Workspace.OUT);
      System.out.printf("%s %s: %d endpoints%n", m.title(), m.version(), m.endpoints().size());
      m.endpoints().forEach(e -> System.out.printf("  %-7s %-40s %s  [%s]%n", e.method(), e.path(), e.operationId(), e.primaryTag()));
      System.out.println("Report: " + Workspace.OUT.resolve("ingest-report.json"));
      printConfigChecklist(m, env);
      return 0;
    }
  }

  @Command(name = "generate", description = "Generate JSON test cases and merge them with the existing ones in testcases/")
  public static class Generate implements Callable<Integer> {
    @Option(names = "--spec", required = true) String spec;
    @Option(names = "--out", description = "Default: testcases/") Path out = Workspace.TESTCASES;
    @Option(names = "--include-tags", split = ",") Set<String> includeTags = Set.of();
    @Option(names = "--exclude-ops", split = ",") Set<String> excludeOps = Set.of();
    @Option(names = "--categories", split = ",",
        description = "POSITIVE,MISSING_REQUIRED,TYPE_MISMATCH,BOUNDARY,ENUM,AUTH,NOT_FOUND (default: all)")
    Set<String> categories = Set.of();
    @Option(names = "--fresh", description = "Ignore stored cases (loses approvals). Default merges.") boolean fresh;

    public Integer call() throws Exception {
      ApiModel model = new IngestService().ingest(spec, Workspace.OUT);
      List<TestCase> generated = new CaseGenerator()
          .generate(model, new CaseGenerator.Options(includeTags, excludeOps, categories))
          .values().stream().flatMap(List::stream).toList();
      CaseStore store = new CaseStore(out);
      CaseDiffer.Result r = new CaseDiffer().diff(fresh ? Map.of() : store.readBySpec(model.specName()), generated);
      store.writeAll(r.merged());
      System.out.printf("%s: %d endpoints -> %d test cases (new %d, changed %d, removed %d, unchanged %d) in %s%n",
          model.title(), model.endpoints().size(), generated.size(), r.added(), r.changed(), r.removed(), r.unchanged(), out);
      System.out.println("Next: review them in the approval UI (java -jar target/api-test-platform.jar serve)");
      return 0;
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

  @Command(name = "services", description = "Generate <Tag>Api endpoint classes and <Tag>Steps step definitions per OpenAPI tag")
  public static class Services implements Callable<Integer> {
    @Option(names = "--spec", required = true) String spec;
    @Option(names = "--out", description = "Api classes. Default: src/test/java/com/qa/automation/api/generated")
    Path out = com.qa.platform.codegen.CodegenService.API_OUT;
    @Option(names = "--steps-out", description = "Step definitions. Default: src/test/java/com/qa/automation/steps/generated")
    Path stepsOut = com.qa.platform.codegen.CodegenService.STEPS_OUT;

    public Integer call() throws Exception {
      var r = new com.qa.platform.codegen.CodegenService().generate(spec, out, stepsOut);
      System.out.printf("%d Api classes (%d operations) -> %s%n", r.apiClasses(), r.operations(), out);
      System.out.printf("%d Steps classes (%d When-steps) -> %s%n", r.stepClasses(), r.steps(), stepsOut);
      System.out.println("Generated features call these steps: When I call the <tag> operation \"<operationId>\"");
      System.out.println("Business flows: wrap the Api classes in services (see the add-api-service skill)");
      return 0;
    }
  }

  @Command(name = "features", description = "Write Cucumber feature files for APPROVED test cases only")
  public static class Features implements Callable<Integer> {
    @Option(names = "--cases") Path cases = Workspace.TESTCASES;
    @Option(names = "--out") Path out = Workspace.FEATURES;

    public Integer call() throws Exception {
      FeatureWriter.Result r = new FeatureWriter().write(new CaseStore(cases).readAll(), out);
      System.out.printf("%d feature files, %d scenarios -> %s%n", r.features(), r.scenarios(), out);
      if (r.scenarios() == 0) System.out.println("No APPROVED cases yet: approve some in the UI first.");
      return 0;
    }
  }

  @Command(name = "rca", description = "Analyse failures from the last test run -> target/rca/rca.json")
  public static class Rca implements Callable<Integer> {
    @Option(names = "--llm", description = "Also ask an LLM for the narrative (LLM_PROVIDER: github-models (default), openai, azure-openai, anthropic; see README 'LLM narrative')") boolean llm;

    public Integer call() throws Exception {
      if (llm) System.setProperty("llm.enabled", "true");
      LlmClient client = new LlmClient();
      RcaEngine.Report r = new RcaEngine(Workspace.EVIDENCE, Workspace.ROOT.resolve("target/run-summary.json"),
          Workspace.HISTORY, Workspace.RCA, new CaseStore(Workspace.TESTCASES), client).run();
      System.out.printf("%d failures -> %d root causes (%d new). By category: %s%n",
          r.failures(), r.groups(), r.newFailures(), r.byCategory());
      System.out.println("Written: " + Workspace.RCA.resolve("rca.json"));
      if (r.groups() > 0 && (!llm || !client.enabled())) {
        if (llm) System.out.println("No token for LLM_PROVIDER=" + client.provider() + ": the narrative stays rule-based.");
        System.out.println("Narrative from your IDE agent (no token needed): run /rca in Copilot agent mode,"
            + " or: java -jar target/api-test-platform.jar ai rca-narrative --mode agent");
      }
      return 0;
    }
  }

  @Command(name = "ai", description = "AI-assisted tasks, by LLM API or by your IDE agent: "
      + "doc-to-openapi, spec-audit, testcase-augment, rca-narrative, triage-summary")
  public static class Ai implements Callable<Integer> {
    @picocli.CommandLine.Parameters(index = "0", description = "Task: ${COMPLETION-CANDIDATES}",
        completionCandidates = AiTaskNames.class) String task;
    @Option(names = "--spec", description = "spec-audit, testcase-augment: spec file or URL") String spec;
    @Option(names = "--doc", description = "doc-to-openapi: API documentation file (Markdown, HTML, text)") String doc;
    @Option(names = "--context", description = "testcase-augment: business rules the spec does not state") String context;
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
        if (doc != null) args.put("doc", doc);
        if (context != null) args.put("context", context);
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
      if (llm && new LlmClient(true).configured() && Files.exists(Workspace.RCA.resolve("rca.json"))) {
        // the triage note too, from the same provider (the agent path: /rca writes it)
        try {
          var t = new com.qa.platform.ai.AiRunner().run(com.qa.platform.ai.AiTasks.create("triage-summary", Map.of()),
              com.qa.platform.ai.AiRunner.Mode.LLM);
          System.out.print(com.qa.platform.ai.AiRunner.format(t));
        } catch (RuntimeException e) {
          System.err.println("[llm] triage summary skipped: " + e.getMessage());
        }
      }
      new Report().call();
      String line = ReportRcaMerger.oneLine(Workspace.ROOT.resolve("target/run-summary.json"), Workspace.RCA.resolve("rca.json"));
      System.out.println("SUMMARY: " + line);
      Json.write(Workspace.ROOT.resolve("target/summary.json"), Map.of("summary", line));
      return 0;
    }
  }
}
