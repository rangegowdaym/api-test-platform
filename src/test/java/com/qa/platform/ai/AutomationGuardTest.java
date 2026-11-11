package com.qa.platform.ai;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Expected;
import com.qa.platform.model.TestCase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/** Generated or healed automation can never drop an approved case or weaken its approved status. */
public class AutomationGuardTest {

  private static TestCase approved(String id, int status) {
    return new TestCase(id, id, "POSITIVE", "P1", List.of("pet"), TestCase.APPROVED, 1, null, null,
        new TestCase.Source("petstore.yaml", null, "/pet/{petId}", "GET", "getPetById"),
        new CaseRequest(Map.of(), Map.of(), Map.of(), "default", null), new Expected(status, null, null, null), null, null);
  }

  private static final String FEATURE = """
      @pet @api
      Feature: Pet
        @TC-a
        Scenario: A
          Given test case "TC-a"
          Then the response status is 200

        @TC-b @smoke
        Scenario: B
          Given test case "TC-b"
          Then the response status is 404
      """;

  private static Map<String, TestCase> cases() {
    Map<String, TestCase> m = new LinkedHashMap<>();
    m.put("TC-a", approved("TC-a", 200));
    m.put("TC-b", approved("TC-b", 404));
    return m;
  }

  @Test
  public void scenarioBlocksAreSplitPerCase() {
    Map<String, String> blocks = AutomationTask.scenarioBlocks(FEATURE);
    assertEquals(blocks.keySet(), java.util.Set.of("TC-a", "TC-b"));
    assertTrue(blocks.get("TC-b").contains("404"));
  }

  @Test
  public void aCompleteFeaturePasses() {
    AutomationTask.checkCoverage("pet", FEATURE, cases());
  }

  @Test
  public void aWeakenedStatusIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> AutomationTask.checkCoverage("pet", FEATURE.replace("is 404", "is 200"), cases()));
  }

  @Test
  public void aDroppedCaseIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> AutomationTask.checkCoverage("pet", FEATURE.substring(0, FEATURE.indexOf("@TC-b")), cases()));
  }

  @Test
  public void healAndAutomationShareTheTask() {
    assertEquals(AiTasks.create("heal", Map.of()).name(), "heal");
    assertEquals(AiTasks.create("automation", Map.of()).name(), "automation");
  }
}
