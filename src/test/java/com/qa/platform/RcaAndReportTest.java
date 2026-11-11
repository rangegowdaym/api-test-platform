package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import com.qa.platform.generate.CaseStore;
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
public class RcaAndReportTest {

  @Test
  public void collectsEveryFailureForTheAiAndMergesTheReport() throws Exception {
    Path root = Files.createTempDirectory("rca");
    Path ev = root.resolve("evidence");
    write(ev, failed("TC-createOrder-missing-required-body_customerId", "POST /orders", 400, 500, null, "Expected status 400 but was 500"));
    write(ev, failed("TC-createOrder-type-mismatch-customerId", "POST /orders", 400, 500, null, "Expected status 400 but was 500"));
    write(ev, failed("TC-getOrderById-positive-1", "GET /orders/{orderId}", 200, null, "java.net.ConnectException", "Connection refused"));

    // no LLM: one UNCLASSIFIED placeholder per failure, waiting for the AI (ai rca / /rca)
    RcaEngine.Report r = new RcaEngine(ev, root.resolve("run-summary.json"), root.resolve("history"), root.resolve("rca"),
        new CaseStore(root.resolve("testcases")), null).run();
    assertEquals(r.failures(), 3);
    assertEquals(r.groups(), 3);
    assertTrue(r.entries().stream().allMatch(e -> "UNCLASSIFIED".equals(e.category())));
    assertTrue(r.entries().stream().allMatch(e -> e.curl() != null));
    assertTrue(Files.exists(root.resolve("rca/rca.json")));
    assertTrue(Files.exists(root.resolve("rca/failures.json")), "facts for the AI task");
    assertTrue(Files.exists(root.resolve("history/results.jsonl")) || Files.list(root.resolve("history")).findAny().isPresent());

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
    assertTrue(page.contains("UNCLASSIFIED"));
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
