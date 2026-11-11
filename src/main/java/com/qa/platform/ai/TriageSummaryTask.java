package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The triage note after a run (prompts/triage-summary.md) -> target/rca/triage.md. */
final class TriageSummaryTask implements AiTask {

  public String name() { return "triage-summary"; }
  public String format() { return "markdown"; }
  public Map<String, String> args() { return Map.of(); }
  public int maxTokens() { return 3000; }
  public String describe() { return "Write the triage note for the last run from target/rca/rca.json -> target/rca/triage.md."; }

  public List<AiRequest> requests() throws Exception {
    Path f = Workspace.RCA.resolve("rca.json");
    if (!Files.exists(f)) throw new IllegalStateException("No target/rca/rca.json: run post-run after the tests first");
    ObjectNode rca = (ObjectNode) Json.MAPPER.readTree(f.toFile());
    if (rca.path("entries").isEmpty()) return List.of();
    // tickets are long and repeat the evidence: leave them out of the prompt
    for (JsonNode e : rca.path("entries")) ((ObjectNode) e).remove("bugTicket");
    StringBuilder evidence = new StringBuilder();
    ArrayNode entries = (ArrayNode) rca.path("entries");
    for (int i = 0; i < Math.min(10, entries.size()); i++) {
      String id = entries.get(i).path("caseId").asText();
      Path ev = Workspace.EVIDENCE.resolve(id + ".json");
      if (Files.exists(ev)) evidence.append("### ").append(id).append('\n').append(AiText.clean(Files.readString(ev), 2500)).append("\n\n");
    }
    return List.of(new AiRequest("triage", "triage-summary", Map.of(
        "rca_json", AiText.clean(Json.pretty(rca), 15000),
        "evidence", evidence.toString())));
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    String md = AiText.unfence(answer, "markdown");
    if (md.length() < 40 || !md.contains("|")) throw new IllegalArgumentException("expected the Markdown triage note with its table");
    Path out = Workspace.RCA.resolve("triage.md");
    Files.writeString(out, "<!-- written by " + by + " from target/rca/rca.json (prompts/triage-summary.md); "
        + "categories come from the rules -->\n\n" + AiText.clean(md, 40000) + "\n");
    return "triage note -> " + out;
  }
}
