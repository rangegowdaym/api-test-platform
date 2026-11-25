package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.qa.platform.common.Hashing;
import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseDiffer;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI designs the test cases for a spec (prompts/testcases.md): positive, negative, boundary, auth, not found and
 * business cases, from OpenAPI/Swagger, Postman or plain API docs. Big specs are split into parts by path.
 *
 * <p>Validation: every case against testcase.schema.json, status forced to PENDING_REVIEW (never approved), source.spec
 * set to the file. Merge ({@link CaseDiffer}) keeps approvals: when the spec has not changed since the last
 * generation, an existing case keeps its stored content and status; human edits always win.
 */
final class TestcasesTask implements AiTask {
  private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(TestcasesTask.class.getResourceAsStream("/schemas/testcase.schema.json"));

  private final String spec;
  private final String context;
  private final Path file;
  private final List<TestCase> collected = new ArrayList<>();
  private int answered;
  private int parts;

  TestcasesTask(String spec, String context) {
    if (spec == null || spec.isBlank()) throw new IllegalArgumentException("testcases needs --spec (a file in specs/ or a path)");
    Path p = Path.of(spec);
    if (!p.isAbsolute() && !Files.exists(p)) p = Workspace.ROOT.resolve(spec);
    this.file = Files.exists(p) ? p : Workspace.SPECS.resolve(spec);
    if (!Files.exists(file)) throw new IllegalArgumentException("No such spec: " + spec + " (copy it into specs/)");
    this.spec = spec;
    this.context = context == null ? "" : context;
  }

  public String name() { return "testcases"; }
  public String format() { return "json"; }
  public Map<String, String> args() { return Map.of("spec", spec, "context", context); }
  public int maxTokens() { return 16000; }
  public String describe() {
    return "Design the test cases for " + file.getFileName() + " (JSON array per part, schema testcase.schema.json). "
        + "They are stored as PENDING_REVIEW in testcases/ for humans to approve in the approval UI.";
  }

  private String specName() { return file.getFileName().toString(); }

  public List<AiRequest> requests() throws Exception {
    List<String> chunks = SpecText.parts(file, 60000);
    parts = chunks.size();
    StringBuilder existing = new StringBuilder();
    for (TestCase t : new CaseStore(Workspace.TESTCASES).readBySpec(specName()).values()) {
      existing.append(t.id()).append(" | ").append(t.status()).append(" | ").append(t.title()).append('\n');
    }
    List<AiRequest> out = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      Map<String, String> vars = new LinkedHashMap<>();
      vars.put("spec_name", specName());
      vars.put("part", (i + 1) + " of " + chunks.size());
      vars.put("spec", chunks.get(i));
      vars.put("existing", AiText.clean(existing.toString(), 20000));
      vars.put("context", Masking.text(context));
      out.add(new AiRequest("part-" + (i + 1), "testcases", vars));
    }
    return out;
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    JsonNode list = AiText.json(answer);
    if (!list.isArray()) list = list.path("cases");
    if (!list.isArray()) throw new IllegalArgumentException("expected a JSON array of test cases");
    String specHash = Hashing.sha256(Files.readString(file)).substring(0, 16);
    List<String> skipped = new ArrayList<>();
    int ok = 0;
    for (JsonNode n : list) {
      var errors = SCHEMA.validate(n);
      if (!errors.isEmpty()) { skipped.add(n.path("id").asText("?") + ": " + errors.stream().limit(2).toList()); continue; }
      TestCase t = Json.MAPPER.convertValue(n, TestCase.class);
      TestCase.Source s = t.source();
      t = new TestCase(t.id(), t.title(), t.category(), t.priority(), t.tags(), TestCase.PENDING, 1, null, null,
          new TestCase.Source(specName(), specHash, s.path(), s.method(), s.operationId()), t.request(), t.expected(),
          new com.qa.platform.model.Review("ai:" + by, "GENERATED", null, java.time.Instant.now()), t.notes());
      collected.add(t.withHash(CaseStore.contentHash(t)));
      ok++;
    }
    answered++;
    if (ok == 0) throw new IllegalArgumentException("no valid case: " + String.join("; ", skipped));
    return request.id() + ": " + ok + " cases" + (skipped.isEmpty() ? "" : ", skipped " + skipped.size() + " invalid: " + String.join("; ", skipped));
  }

  /** Merge with the stored cases of this spec once all parts are in (removals only after a complete answer). */
  public String finish() throws Exception {
    CaseStore store = new CaseStore(Workspace.TESTCASES);
    Map<String, TestCase> stored = store.readBySpec(specName());
    String specHash = Hashing.sha256(Files.readString(file)).substring(0, 16);
    Map<String, TestCase> fresh = new LinkedHashMap<>();
    for (TestCase t : collected) {
      TestCase old = stored.get(t.id());
      // same spec as last time: the existing case (and its approval) stays as it is
      boolean specUnchanged = old != null && old.source() != null && specHash.equals(old.source().specHash());
      fresh.putIfAbsent(t.id(), specUnchanged ? old : t);
    }
    boolean complete = answered >= parts;
    Map<String, TestCase> base = complete ? stored : new LinkedHashMap<>();
    if (!complete) for (TestCase t : fresh.values()) if (stored.containsKey(t.id())) base.put(t.id(), stored.get(t.id()));
    CaseDiffer.Result r = new CaseDiffer().diff(base, new ArrayList<>(fresh.values()));
    store.writeAll(r.merged());
    return String.format("%s: %d cases (new %d, changed %d, removed %d, unchanged %d) -> review in the approval UI%s",
        specName(), fresh.size(), r.added(), r.changed(), r.removed(), r.unchanged(),
        complete ? "" : " (not every part answered: nothing marked obsolete)");
  }
}
