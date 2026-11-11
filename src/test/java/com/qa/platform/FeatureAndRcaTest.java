package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.Masking;
import com.qa.platform.feature.FeatureWriter;
import com.qa.platform.generate.CaseGenerator;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.ingest.IngestService;
import com.qa.platform.model.ApiModel;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import com.qa.platform.model.TestCase;
import com.qa.platform.rca.RcaEngine;
import com.qa.platform.report.ReportRcaMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/** mvn test -Punit */
public class FeatureAndRcaTest {

  @Test
  public void onlyApprovedCasesBecomeScenarios() throws Exception {
    ApiModel m = new IngestService().ingest("src/test/resources/fixtures/sample-api.yaml", Files.createTempDirectory("i"));
    List<TestCase> all = new CaseGenerator().generate(m, CaseGenerator.Options.all()).values().stream().flatMap(List::stream).toList();
    List<TestCase> mixed = new java.util.ArrayList<>(all);
    mixed.set(0, all.get(0).withStatus(TestCase.APPROVED));
    mixed.set(1, all.get(1).withStatus(TestCase.APPROVED));
    Path out = Files.createTempDirectory("features");
    FeatureWriter.Result r = new FeatureWriter().write(mixed, out);
    assertEquals(r.scenarios(), 2);
    String text = Files.readString(r.files().get(0));
    assertTrue(text.startsWith("# GENERATED"));
    assertTrue(text.contains("Given test case \"" + all.get(0).id() + "\""));
    assertTrue(text.contains("When I call the " + all.get(0).tags().get(0) + " operation \"" + all.get(0).source().operationId() + "\""));
  }

  @Test
  public void classifiesAndGroupsFailures() throws Exception {
    Path root = Files.createTempDirectory("rca");
    Path ev = root.resolve("evidence");
    // two 500s on the same endpoint (one group), one connection refused (environment), one 401 (auth)
    write(ev, failed("TC-createOrder-missing-required-body_customerId", "POST /orders", 400, 500, null, "Expected status 400 but was 500"));
    write(ev, failed("TC-createOrder-type-mismatch-customerId", "POST /orders", 400, 500, null, "Expected status 400 but was 500"));
    write(ev, failed("TC-getOrderById-positive-1", "GET /orders/{orderId}", 200, null, "java.net.ConnectException", "Connection refused"));
    write(ev, failed("TC-listOrders-positive-1", "GET /orders", 200, 401, null, "Expected status 200 but was 401"));

    RcaEngine.Report r = new RcaEngine(ev, root.resolve("run-summary.json"), root.resolve("history"), root.resolve("rca"),
        new CaseStore(root.resolve("testcases")), new LlmClient()).run();

    assertEquals(r.failures(), 4);
    assertEquals(r.groups(), 3);
    Map<String, RcaEntry> byCat = new java.util.HashMap<>();
    r.entries().forEach(e -> byCat.put(e.category(), e));
    assertEquals(byCat.get("SERVER_ERROR").relatedCaseIds().size(), 1);
    assertTrue(byCat.containsKey("ENVIRONMENT"));
    assertTrue(byCat.containsKey("AUTH_CONFIG"));
    assertTrue(Files.exists(root.resolve("rca/rca.json")));

    // the RCA is merged into the page written by ModernCucumberReporter, inside its report-data JSON block
    Path index = root.resolve("reports/index.html");
    Files.createDirectories(index.getParent());
    Files.writeString(index, "<html><body><script type=\"application/json\" id=\"report-data\">{\"title\":\"t\",\"tests\":[]}</script>"
        + "<script>/* app.js */</script></body></html>");
    ReportRcaMerger.Result merged = new ReportRcaMerger().merge(index, root.resolve("rca/rca.json"));
    assertTrue(merged.merged());
    assertEquals(merged.entries(), 3);
    String page = Files.readString(index);
    assertTrue(page.contains("\"rca\":{"));
    assertTrue(page.contains("SERVER_ERROR"));
    assertTrue(page.contains("<script>/* app.js */</script>"), "the app script must be untouched");
    // the data block still closes exactly once: no "<" from bug tickets/bodies leaks into the script element
    String block = page.substring(page.indexOf("id=\"report-data\">"), page.indexOf("</script>"));
    assertTrue(!block.contains("<"), "unescaped < inside report-data");
    // idempotent: merging again replaces the rca field instead of adding a second one
    new ReportRcaMerger().merge(index, root.resolve("rca/rca.json"));
    assertEquals(Files.readString(index).split("\"rca\":\\{", -1).length, 2);
  }

  @Test
  public void masksSecrets() {
    assertEquals(Masking.headers(Map.of("Authorization", "Bearer abc")).get("Authorization"), "***");
    assertTrue(Masking.text("{\"password\":\"hunter2\",\"name\":\"x\"}").contains("\"password\":\"***\""));
    assertTrue(Masking.text("token is Bearer eyJhbGciOi.x.y").endsWith("Bearer ***"));
  }

  private static Evidence failed(String id, String endpoint, int expected, Integer actual, String errType, String msg) {
    return new Evidence(id, id, "orders.feature", List.of("@" + id), "qa", "FAILED", 0, 120, 0,
        new Evidence.HttpRequest(endpoint.split(" ")[0], "https://qa.example.com/api/v3" + endpoint.split(" ")[1], Map.of(), "{}"),
        actual == null ? null : new Evidence.HttpResponse(actual, Map.of(), "{\"message\":\"boom\"}", 80L),
        "Then the response status should be " + expected, errType == null ? "java.lang.AssertionError" : errType, msg, "",
        expected, endpoint, "curl -X " + endpoint);
  }

  private static void write(Path dir, Evidence e) { Json.write(dir.resolve(e.caseId() + ".json"), e); }
}
