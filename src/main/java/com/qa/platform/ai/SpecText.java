package com.qa.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A spec as prompt text, read as-is (the AI understands OpenAPI, Swagger, Postman and plain docs).
 * Big OpenAPI/Swagger files are split by paths into parts that each carry the shared parts (info, servers,
 * security, components/definitions), so every prompt is self-contained.
 */
final class SpecText {
  private SpecText() {}

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  static String read(Path file, int maxChars) throws Exception {
    return AiText.clean(Files.readString(file), maxChars);
  }

  static List<String> parts(Path file, int maxChars) throws Exception {
    String text = Files.readString(file);
    if (text.length() <= maxChars) return List.of(AiText.clean(text, maxChars));
    JsonNode root;
    try {
      root = YAML.readTree(text);   // YAML parser reads JSON too
    } catch (Exception e) {
      root = null;
    }
    if (root == null || !root.has("paths")) {
      // not OpenAPI/Swagger: split plain text on blank lines
      List<String> out = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      for (String para : text.split("\\n\\s*\\n")) {
        if (cur.length() + para.length() > maxChars && cur.length() > 0) { out.add(AiText.clean(cur.toString(), maxChars)); cur.setLength(0); }
        cur.append(para).append("\n\n");
      }
      if (cur.length() > 0) out.add(AiText.clean(cur.toString(), maxChars));
      return out;
    }
    ObjectNode shared = ((ObjectNode) root).deepCopy();
    shared.remove("paths");
    String sharedText = YAML.writeValueAsString(shared);
    int budget = Math.max(10000, maxChars - sharedText.length());
    List<String> out = new ArrayList<>();
    ObjectNode paths = YAML.createObjectNode();
    int size = 0;
    for (Iterator<Map.Entry<String, JsonNode>> it = root.path("paths").fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      int len = YAML.writeValueAsString(e.getValue()).length();
      if (size + len > budget && size > 0) { out.add(part(shared, paths, maxChars)); paths = YAML.createObjectNode(); size = 0; }
      paths.set(e.getKey(), e.getValue());
      size += len;
    }
    if (size > 0) out.add(part(shared, paths, maxChars));
    return out;
  }

  private static String part(ObjectNode shared, ObjectNode paths, int maxChars) throws Exception {
    ObjectNode doc = shared.deepCopy();
    doc.set("paths", paths);
    return AiText.clean(YAML.writeValueAsString(doc), maxChars + 40000);
  }
}
