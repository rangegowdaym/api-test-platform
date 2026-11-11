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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/** AI tasks: the same validation whether an LLM API or the IDE agent (Copilot agent mode) answers. */
public class AiTasksTest {

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

  /** A "files" task, like automation: the agent writes real files into the answer folder. */
  static final class FakeFilesTask implements AiTask {
    String received;
    private final String name = "fake-files-" + System.nanoTime();
    public String name() { return name; }
    public String format() { return "files"; }
    public Map<String, String> args() { return Map.of(); }
    public String describe() { return "test"; }
    public List<AiRequest> requests() { return List.of(new AiRequest("orders", "spec-audit", Map.of("spec", "x"))); }
    public String apply(AiRequest r, String answer, String by) { received = answer; return "ok"; }
  }

  @Test
  public void filesAnswersAreReadFromTheAnswerFolder() throws Exception {
    Path root = com.qa.platform.common.Workspace.ROOT.resolve("target/ai-test-" + System.nanoTime());
    FakeFilesTask task = new FakeFilesTask();
    AiRunner runner = new AiRunner(root, new LlmClient(false));
    runner.run(task, AiRunner.Mode.AGENT);
    String md = Files.readString(root.resolve(task.name()).resolve("requests.md"));
    assertTrue(md.contains("Answer folder:"));
    Path answer = root.resolve(task.name()).resolve("answers/orders/src/test/resources/features/generated/orders.feature");
    Files.createDirectories(answer.getParent());
    Files.writeString(answer, "Feature: Orders\n");
    // apply needs the task registered by name; call the folder reader through a direct apply instead
    JsonNode files = Json.MAPPER.readTree(invokeFilesAsJson(root.resolve(task.name()).resolve("answers/orders")));
    assertEquals(files.path("files").get(0).path("path").asText(), "src/test/resources/features/generated/orders.feature");
    assertEquals(files.path("files").get(0).path("content").asText(), "Feature: Orders\n");
  }

  private static String invokeFilesAsJson(Path dir) throws Exception {
    var m = AiRunner.class.getDeclaredMethod("filesAsJson", Path.class);
    m.setAccessible(true);
    return (String) m.invoke(null, dir);
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
    assertThrows(IllegalArgumentException.class, () -> AiTasks.create("testcases", Map.of()));
    assertEquals(AiRunner.Mode.of("agent"), AiRunner.Mode.AGENT);
    assertEquals(AiRunner.Mode.of(null), AiRunner.Mode.AUTO);
  }
}
