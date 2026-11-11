package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.Masking;

/** Helpers to read model/agent answers. */
final class AiText {
  private AiText() {}

  /** The content of the first ```lang (or any) fence, or the whole text when there is none. */
  static String unfence(String answer, String lang) {
    if (answer == null) return "";
    String t = answer.strip();
    int i = t.indexOf("```");
    if (i < 0) return t;
    int nl = t.indexOf('\n', i);
    if (nl < 0) return t;
    int end = t.indexOf("```", nl);
    String body = end < 0 ? t.substring(nl + 1) : t.substring(nl + 1, end);
    // a markdown answer that merely contains code blocks must stay whole
    if ("markdown".equals(lang) && !t.startsWith("```")) return t;
    return body.strip();
  }

  static JsonNode json(String answer) {
    return LlmClient.extractJson(answer)
        .orElseThrow(() -> new IllegalArgumentException("answer is not JSON"));
  }

  static String clean(String s, int max) {
    String m = Masking.text(s == null ? "" : s.strip());
    return m.length() <= max ? m : m.substring(0, max) + "\n...[truncated]";
  }

  static String safeId(String id) { return id.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
