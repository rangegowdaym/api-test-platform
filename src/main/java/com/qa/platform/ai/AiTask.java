package com.qa.platform.ai;

import java.util.List;
import java.util.Map;

/**
 * One AI-assisted job of the platform. The same task runs two ways (see {@link AiRunner}):
 * <ul>
 *   <li><b>llm</b>: the platform sends each request to the configured LLM API ({@code LlmClient}: GitHub Models,
 *       a company OpenAI-compatible gateway, Azure OpenAI, Anthropic)</li>
 *   <li><b>agent</b>: the platform writes the requests to {@code target/ai/<task>/requests.md}; the IDE agent
 *       (GitHub Copilot agent mode, prompt /ai) answers them into {@code target/ai/<task>/answers/}, then
 *       {@code ai apply} validates and applies them</li>
 * </ul>
 * Either way {@link #apply} is the only way an answer changes anything, so validation is the same for both.
 */
public interface AiTask {

  /** CLI name, e.g. "testcases". */
  String name();

  /** Expected answer format: "json", "yaml" or "markdown" (also the answer file extension). */
  String format();

  /** Arguments to rebuild the task at --apply time (stored in the manifest). */
  Map<String, String> args();

  /** One prompt per unit of work. Ids must be stable between export and apply. */
  List<AiRequest> requests() throws Exception;

  /**
   * Validate and apply one answer. Returns a one-line message; throws IllegalArgumentException with the reason
   * when the answer is rejected (nothing is changed then).
   */
  String apply(AiRequest request, String answer, String by) throws Exception;

  /** After all answers: refresh derived files (report, ...). Returns a message or null. */
  default String finish() throws Exception { return null; }

  default int maxTokens() { return 2000; }

  /** What a reviewer should know about where the output goes. */
  String describe();

  /** A request: prompt name in prompts/ and its variables (already masked by the task). */
  record AiRequest(String id, String prompt, Map<String, String> vars) {}
}
