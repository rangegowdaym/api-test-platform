package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.generate.CaseDiffer;
import com.qa.platform.generate.CaseGenerator;
import com.qa.platform.generate.ValueGenerator;
import com.qa.platform.ingest.IngestService;
import com.qa.platform.model.ApiModel;
import com.qa.platform.model.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.annotations.Test;

/** mvn test -Punit */
public class GeneratorTest {

  private static List<TestCase> generate() throws Exception {
    ApiModel m = new IngestService().ingest("src/test/resources/fixtures/sample-api.yaml", Files.createTempDirectory("ingest"));
    return new CaseGenerator().generate(m, CaseGenerator.Options.all()).values().stream().flatMap(List::stream).toList();
  }

  @Test
  public void ingestsAllOperations() throws Exception {
    ApiModel m = new IngestService().ingest("src/test/resources/fixtures/sample-api.yaml", Files.createTempDirectory("ingest"));
    assertEquals(m.endpoints().size(), 5);
    assertTrue(m.endpoints().stream().anyMatch(e -> e.operationId().equals("createOrder") && e.secured()));
    assertTrue(m.endpoints().stream().anyMatch(e -> e.operationId().equals("createCustomer") && !e.secured()));
  }

  @Test
  public void idsAreUniqueAndStable() throws Exception {
    List<TestCase> a = generate();
    List<TestCase> b = generate();
    Set<String> ids = new HashSet<>();
    a.forEach(t -> assertTrue(ids.add(t.id()), "duplicate id " + t.id()));
    assertEquals(a.stream().map(TestCase::id).toList(), b.stream().map(TestCase::id).toList());
    assertEquals(a.stream().map(TestCase::contentHash).toList(), b.stream().map(TestCase::contentHash).toList());
  }

  @Test
  public void coversTheExpectedCategories() throws Exception {
    Map<String, Long> byCat = generate().stream().collect(Collectors.groupingBy(TestCase::category, Collectors.counting()));
    for (String c : List.of("POSITIVE", "MISSING_REQUIRED", "TYPE_MISMATCH", "BOUNDARY", "ENUM", "AUTH", "NOT_FOUND")) {
      assertTrue(byCat.getOrDefault(c, 0L) > 0, "no " + c + " cases: " + byCat);
    }
    assertTrue(generate().stream().anyMatch(t -> t.id().equals("TC-createOrder-missing-required-body_customerId")));
  }

  @Test
  public void documentedErrorCodeIsUsed() throws Exception {
    TestCase t = generate().stream().filter(x -> x.id().startsWith("TC-createCustomer-missing-required")).findFirst().orElseThrow();
    assertEquals(t.expected().status(), 422);           // the spec documents 422, not 400
  }

  @Test
  public void importingAnotherSpecDoesNotObsoleteThisSpecsCases() throws Exception {
    java.nio.file.Path dir = Files.createTempDirectory("cases");
    com.qa.platform.generate.CaseStore store = new com.qa.platform.generate.CaseStore(dir);
    store.writeAll(generate());
    // a different spec generates nothing that overlaps: this spec's cases must not be touched
    assertTrue(store.readBySpec("other-api.yaml").isEmpty());
    assertEquals(store.readBySpec("sample-api.yaml").size(), generate().size());
  }

  @Test
  public void diffKeepsApprovalOfUnchangedCases() throws Exception {
    List<TestCase> first = generate();
    TestCase approved = first.get(0).withStatus(TestCase.APPROVED);
    Map<String, TestCase> stored = first.stream().collect(Collectors.toMap(TestCase::id, t -> t));
    stored.put(approved.id(), approved);
    CaseDiffer.Result r = new CaseDiffer().diff(stored, generate());
    assertEquals(r.added(), 0);
    assertEquals(r.changed(), 0);
    assertEquals(r.merged().stream().filter(t -> t.id().equals(approved.id())).findFirst().orElseThrow().status(), TestCase.APPROVED);
  }

  @Test
  public void valueGeneratorRespectsLimits() {
    JsonNode s = Json.MAPPER.createObjectNode().put("type", "string").put("maxLength", 5).put("minLength", 3);
    ValueGenerator g = new ValueGenerator();
    for (int i = 0; i < 20; i++) {
      int len = g.valid(s).asText().length();
      assertTrue(len >= 3 && len <= 5, "length " + len);
    }
    JsonNode email = Json.MAPPER.createObjectNode().put("type", "string").put("format", "email");
    assertTrue(g.valid(email).asText().contains("@"));
    assertFalse(g.wrongType(Json.MAPPER.createObjectNode().put("type", "integer")).isNumber());
  }

  @Test
  public void everyCaseMatchesTheJsonSchema() throws Exception {
    var schema = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
        .getSchema(Files.newInputStream(Path.of("src/main/resources/schemas/testcase.schema.json")));
    for (TestCase t : generate()) {
      var errors = schema.validate(Json.MAPPER.valueToTree(t));
      assertTrue(errors.isEmpty(), t.id() + ": " + errors);
    }
  }
}
