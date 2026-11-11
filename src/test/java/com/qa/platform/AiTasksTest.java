package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.ai.AiRunner;
import com.qa.platform.ai.AiTask;
import com.qa.platform.ai.AiTasks;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.model.RcaEntry;
import com.qa.platform.rca.LlmRcaEnricher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/** AI tasks: the same validation whether an LLM API or the IDE agent answers. */
public class AiTasksTest {

  private static RcaEntry entry(String id) {
    return new RcaEntry(id, List.of(), "scenario", "POST /orders", "SERVER_ERROR", 0.9, "sig",
        "rule summary", "rule cause", "rule fix", "api-dev", true, "run-1", List.of("PASSED"),
        "target/evidence/" + id + ".json", "curl -X POST", "ticket", List.of(), "rules");
  }

  @Test
  public void narrativeMergeKeepsCategoryOwnerAndMasksText() throws Exception {
    JsonNode a = Json.MAPPER.readTree("""
        {"category":"FUNCTIONAL_BUG","owner":"someone","summary":"POST /orders 500 on missing customerId",
         "probableCause":"Header was Authorization: Bearer abc.def.ghi","suggestedFix":"Validate the body","confidence":0.95}""");
    RcaEntry m = LlmRcaEnricher.merge(entry("TC-1"), a, "rules+copilot-agent");
    assertEquals(m.category(), "SERVER_ERROR");
    assertEquals(m.owner(), "api-dev");
    assertFalse(m.probableCause().contains("abc.def.ghi"), "tokens must be masked");
    assertEquals(m.analysedBy(), "rules+copilot-agent");
  }

  @Test
  public void doubtfulNarrativeNeverRaisesConfidence() throws Exception {
    JsonNode a = Json.MAPPER.readTree("{\"probableCause\":\"Category may be wrong: body says token expired\",\"confidence\":0.99}");
    assertEquals(LlmRcaEnricher.merge(entry("TC-1"), a, "x").confidence(), 0.9, 1e-9);
  }

  /** A fake task: records applied answers, rejects answers without "ok". */
  static final class FakeTask implements AiTask {
    final List<String> applied = new ArrayList<>();
    private final String name = "fake-" + System.nanoTime();
    public String name() { return name; }
    public String format() { return "json"; }
    public Map<String, String> args() { return Map.of(); }
    public String describe() { return "test"; }
    public List<AiRequest> requests() {
      return List.of(new AiRequest("A", "spec-audit", Map.of("spec", "x")), new AiRequest("B/odd id", "spec-audit", Map.of("spec", "y")));
    }
    public String apply(AiRequest r, String answer, String by) {
      if (!answer.contains("ok")) throw new IllegalArgumentException("not ok");
      applied.add(r.id() + "@" + by);
      return "applied " + r.id();
    }
  }

  @Test
  public void agentModeWritesRequestsAndAnswerFiles() throws Exception {
    Path root = Files.createTempDirectory("ai");
    FakeTask task = new FakeTask();
    AiRunner.Result r = new AiRunner(root, new LlmClient(false)).run(task, AiRunner.Mode.AGENT);
    assertEquals(r.mode(), "agent");
    assertEquals(r.requests(), 2);
    String md = Files.readString(root.resolve(task.name()).resolve("requests.md"));
    assertTrue(md.contains("## Request A"));
    assertTrue(md.contains("answers/B_odd_id.json"), "ids are made file-safe");
    assertTrue(md.contains("--apply"));
    assertTrue(Files.exists(root.resolve(task.name()).resolve("manifest.json")));
  }

  @Test
  public void llmModeWithoutTokenSaysHowToUseTheAgent() {
    // LLM_PROVIDER=agent has no token by definition
    System.setProperty("llm.provider", "agent");
    try {
      IllegalStateException e = org.testng.Assert.expectThrows(IllegalStateException.class,
          () -> new AiRunner(Path.of("target/ai-test"), new LlmClient(true)).run(new FakeTask(), AiRunner.Mode.LLM));
      assertTrue(e.getMessage().contains("--mode agent"));
    } finally {
      System.clearProperty("llm.provider");
    }
  }

  @Test
  public void unknownTasksAndMissingArgumentsAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> AiTasks.create("approve-everything", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> AiTasks.create("spec-audit", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> AiTasks.create("doc-to-openapi", Map.of()));
    assertEquals(AiRunner.Mode.of("agent"), AiRunner.Mode.AGENT);
    assertEquals(AiRunner.Mode.of(null), AiRunner.Mode.AUTO);
  }
}
