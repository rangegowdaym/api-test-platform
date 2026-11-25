package com.qa.platform.approval;

import com.qa.platform.ai.AiRunner;
import com.qa.platform.ai.AiTasks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Every AI button of the UI goes through here, so the UI behaves the same with or without an LLM token:
 * token on the server -> the task is answered now; no token -> it is queued for Copilot agent mode (/ai) and the UI
 * shows it under "Waiting for Copilot" until the agent has applied the answers. One AI task at a time.
 */
@Service
public class AiService {

  public synchronized AiRunner.Result run(String task, Map<String, String> args) throws Exception {
    return new AiRunner().run(AiTasks.create(task, args == null ? Map.of() : args), AiRunner.Mode.AUTO);
  }

  public Map<String, Object> status() throws Exception {
    AiRunner runner = new AiRunner();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("llmAvailable", runner.llmAvailable());
    out.put("answeredBy", runner.llmName());
    out.put("tasks", AiTasks.NAMES);
    out.put("pending", runner.pending());
    out.put("hint", AiRunner.AGENT_HINT);
    return out;
  }

  /** Applies answers Copilot wrote but did not apply yet ("Apply answers" in the UI = "ai apply" in the terminal). */
  public synchronized List<AiRunner.Result> applyAll(String actor) throws Exception {
    return new AiRunner().applyAll("copilot-agent(" + actor + ")");
  }
}
