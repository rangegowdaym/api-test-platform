package com.qa.platform.ai;

import java.util.List;
import java.util.Map;

/** Registry of the AI tasks (CLI "ai <task>", the approval UI's AI assist, the agents' handoff). */
public final class AiTasks {
  private AiTasks() {}

  public static final List<String> NAMES = List.of("doc-to-openapi", "spec-audit", "testcase-augment", "rca-narrative", "triage-summary");

  public static AiTask create(String name, Map<String, String> args) {
    return switch (name) {
      case "doc-to-openapi" -> new DocToOpenApiTask(args.get("doc"));
      case "spec-audit" -> new SpecAuditTask(args.get("spec"));
      case "testcase-augment" -> new TestcaseAugmentTask(args.get("spec"), args.get("context"));
      case "rca-narrative" -> new RcaNarrativeTask();
      case "triage-summary" -> new TriageSummaryTask();
      default -> throw new IllegalArgumentException("Unknown AI task '" + name + "'. Tasks: " + NAMES);
    };
  }
}
