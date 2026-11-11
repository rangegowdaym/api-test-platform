package com.qa.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads versioned prompt files from prompts/ (falls back to the copy bundled in the jar under /prompts).
 * A prompt file has a "## System" and a "## User" section; {{name}} placeholders are filled from vars.
 */
public final class PromptLibrary {

  public record Prompt(String system, String user) {}

  private PromptLibrary() {}

  public static Prompt load(String name, Map<String, String> vars) {
    String raw = read(name);
    String system = section(raw, "## System");
    String user = section(raw, "## User");
    for (Map.Entry<String, String> v : vars.entrySet()) {
      String value = v.getValue() == null ? "" : v.getValue();
      system = system.replace("{{" + v.getKey() + "}}", value);
      user = user.replace("{{" + v.getKey() + "}}", value);
    }
    return new Prompt(system.trim(), user.trim());
  }

  private static String read(String name) {
    Path file = Workspace.PROMPTS.resolve(name + ".md");
    try {
      if (Files.exists(file)) return Files.readString(file);
      try (InputStream in = PromptLibrary.class.getResourceAsStream("/prompts/" + name + ".md")) {
        if (in == null) throw new IllegalArgumentException("Prompt not found: " + name);
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String section(String raw, String heading) {
    int i = raw.indexOf(heading);
    if (i < 0) return "";
    int start = raw.indexOf('\n', i) + 1;
    int next = raw.indexOf("\n## ", start);
    return next < 0 ? raw.substring(start) : raw.substring(start, next);
  }
}
