package com.qa.platform.ai;

import java.util.List;
import java.util.Map;

/** Registry of the AI tasks (CLI "ai <task>", the approval UI's AI assist, the agents' handoff). */
public final class AiTasks {
  private AiTasks() {}

  public static final List<String> NAMES = List.of("testcases", "spec-audit", "automation", "rca", "heal");

  public static AiTask create(String name, Map<String, String> args) {
    return switch (name) {
      case "testcases" -> new TestcasesTask(args.get("spec"), args.get("context"));
      case "spec-audit" -> new SpecAuditTask(args.get("spec"));
      case "automation" -> new AutomationTask(args.get("tag"), false);
      case "heal" -> new AutomationTask(args.get("tag"), true);
      case "rca" -> new RcaTask();
      default -> throw new IllegalArgumentException("Unknown AI task '" + name + "'. Tasks: " + NAMES);
    };
  }
}
