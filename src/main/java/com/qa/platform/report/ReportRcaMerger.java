package com.qa.platform.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Masking;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the RCA to the report written by {@code org.rg.reporter.ModernCucumberReporter}.
 *
 * <p>The reporter writes one self-contained {@code reports/index.html} during {@code mvn test}; the RCA engine
 * runs afterwards. This class takes the JSON from {@code <script type="application/json" id="report-data">},
 * sets its {@code rca} field from {@code target/rca/rca.json} (secrets masked) and writes the page back with
 * the same script-safe escaping the reporter uses. The report UI (app.js) shows it in the RCA view and in the
 * RCA tab of each failed test, matched through the scenario's {@code @TC-...} tag.
 *
 * <p>Running it twice is safe: the {@code rca} field is replaced, never appended.
 */
public class ReportRcaMerger {

  private static final Pattern DATA = Pattern.compile(
      "(<script type=\"application/json\" id=\"report-data\">)(.*?)(</script>)", Pattern.DOTALL);

  public record Result(Path index, boolean merged, int entries, String message) {}

  public Result merge(Path reportIndex, Path rcaJson) throws IOException {
    if (!Files.exists(reportIndex)) {
      return new Result(reportIndex, false, 0, "No report at " + reportIndex
          + " - run the tests first (the ModernCucumberReporter plugin writes it during mvn test).");
    }
    String page = Files.readString(reportIndex);
    Matcher m = DATA.matcher(page);
    if (!m.find()) {
      return new Result(reportIndex, false, 0, reportIndex + " has no report-data block: is it a ModernCucumberReporter page?");
    }
    ObjectNode data = (ObjectNode) Json.MAPPER.readTree(m.group(2));
    boolean hasRca = Files.exists(rcaJson);
    int entries = 0;
    if (hasRca) {
      JsonNode rca = Json.MAPPER.readTree(Masking.text(Files.readString(rcaJson)));
      data.set("rca", rca);
      entries = rca.path("entries").size();
    } else {
      data.remove("rca");
    }
    String json = forScript(Json.MAPPER.writeValueAsString(data));
    // substring splice, not Matcher.replaceFirst: the JSON contains '$' and '\\' that must stay literal
    Files.writeString(reportIndex, page.substring(0, m.start(2)) + json + page.substring(m.end(2)));
    return new Result(reportIndex, hasRca, entries, hasRca
        ? entries + " RCA entries added to " + reportIndex
        : "No " + rcaJson + " yet: report left without RCA");
  }

  /** Same escaping as org.rg.reporter.HtmlReportWriter#serializeForScript, so the JSON cannot close its script tag. */
  static String forScript(String json) {
    return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
  }

  /** One line for CI and the UI: "142 tests, 131 passed, 11 failed, 4 RCA groups (1 new)". */
  public static String oneLine(Path runSummary, Path rcaJson) throws IOException {
    JsonNode run = Files.exists(runSummary) ? Json.MAPPER.readTree(runSummary.toFile()) : Json.MAPPER.createObjectNode();
    JsonNode rca = Files.exists(rcaJson) ? Json.MAPPER.readTree(rcaJson.toFile()) : Json.MAPPER.createObjectNode();
    long passed = 0, failed = 0, total = 0;
    for (JsonNode s : run.path("scenarios")) {
      total++;
      if ("PASSED".equals(s.path("status").asText())) passed++;
      else if ("FAILED".equals(s.path("status").asText())) failed++;
    }
    return total + " tests, " + passed + " passed, " + failed + " failed, "
        + rca.path("groups").asInt(0) + " RCA groups (" + rca.path("newFailures").asInt(0) + " new)";
  }
}
