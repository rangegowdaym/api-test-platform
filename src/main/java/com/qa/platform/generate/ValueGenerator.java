package com.qa.platform.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.datafaker.Faker;

/** Schema-driven test data. Seeded, so the same spec always produces the same cases (stable diffs and reviews). */
public class ValueGenerator {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final int MAX_DEPTH = 6;
  private final Faker faker;

  public ValueGenerator() { this(42L); }

  public ValueGenerator(long seed) { this.faker = new Faker(new Random(seed)); }

  /** A value that satisfies the schema. Prefers example -> default -> enum[0] -> generated. */
  public JsonNode valid(JsonNode s) { return valid(s, null, 0); }

  private JsonNode valid(JsonNode s, String fieldName, int depth) {
    if (s == null || s.isMissingNode() || s.isNull()) return F.nullNode();
    if (s.has("example") && !s.get("example").isNull()) return s.get("example");
    if (s.has("default")) return s.get("default");
    if (s.has("enum") && s.get("enum").size() > 0) return s.get("enum").get(0);
    if (s.has("allOf")) return mergeAllOf(s.get("allOf"), depth);
    if (s.has("oneOf") && s.get("oneOf").size() > 0) return valid(s.get("oneOf").get(0), fieldName, depth + 1);
    if (s.has("anyOf") && s.get("anyOf").size() > 0) return valid(s.get("anyOf").get(0), fieldName, depth + 1);
    if (depth > MAX_DEPTH) return F.nullNode();

    return switch (type(s)) {
      case "object" -> {
        ObjectNode o = F.objectNode();
        Iterator<Map.Entry<String, JsonNode>> it = s.path("properties").fields();
        while (it.hasNext()) {
          Map.Entry<String, JsonNode> p = it.next();
          if (p.getValue().path("readOnly").asBoolean(false)) continue;
          o.set(p.getKey(), valid(p.getValue(), p.getKey(), depth + 1));
        }
        yield o;
      }
      case "array" -> {
        ArrayNode a = F.arrayNode();
        int n = Math.max(1, s.path("minItems").asInt(1));
        for (int i = 0; i < n; i++) a.add(valid(s.path("items"), fieldName, depth + 1));
        yield a;
      }
      case "integer" -> F.numberNode(integer(s));
      case "number" -> F.numberNode(number(s));
      case "boolean" -> F.booleanNode(true);
      default -> F.textNode(string(s, fieldName));
    };
  }

  public static String type(JsonNode s) {
    if (s.has("type")) {
      JsonNode t = s.get("type");
      if (t.isArray()) {                                  // OpenAPI 3.1: ["string","null"]
        for (JsonNode x : t) if (!"null".equals(x.asText())) return x.asText();
      }
      return t.asText();
    }
    if (s.has("properties")) return "object";
    if (s.has("items")) return "array";
    return "string";
  }

  private JsonNode mergeAllOf(JsonNode parts, int depth) {
    ObjectNode merged = F.objectNode();
    for (JsonNode part : parts) {
      JsonNode v = valid(part, null, depth + 1);
      if (v.isObject()) merged.setAll((ObjectNode) v);
    }
    return merged;
  }

  private long integer(JsonNode s) {
    long min = s.has("minimum") ? s.get("minimum").asLong() + (s.path("exclusiveMinimum").asBoolean(false) ? 1 : 0) : 1;
    long max = s.has("maximum") ? s.get("maximum").asLong() - (s.path("exclusiveMaximum").asBoolean(false) ? 1 : 0) : Math.max(min + 1000, 1000);
    if (max < min) return min;
    return min + (long) faker.number().numberBetween(0, (int) Math.min(Integer.MAX_VALUE - 1, max - min + 1));
  }

  private double number(JsonNode s) {
    double min = s.path("minimum").asDouble(1), max = s.path("maximum").asDouble(min + 1000);
    return Math.round((min + (max - min) / 2) * 100.0) / 100.0;
  }

  String string(JsonNode s, String fieldName) {
    String name = fieldName == null ? "" : fieldName.toLowerCase();
    String v = switch (s.path("format").asText("")) {
      case "email" -> faker.internet().emailAddress();
      case "uuid" -> UUID.nameUUIDFromBytes(faker.lorem().word().getBytes()).toString();
      case "date" -> "2026-01-15";
      case "date-time" -> "2026-01-15T10:00:00Z";
      case "uri", "url" -> "https://example.com/" + faker.lorem().word();
      case "ipv4" -> "10.0.0.1";
      case "password" -> "Passw0rd!" + faker.number().digits(3);
      case "byte" -> "dGVzdA==";
      default -> byName(name);
    };
    int min = s.path("minLength").asInt(0);
    int max = s.has("maxLength") ? s.get("maxLength").asInt() : Integer.MAX_VALUE;
    StringBuilder sb = new StringBuilder(v);
    while (sb.length() < min) sb.append('a');
    String out = sb.toString();
    return out.length() > max ? out.substring(0, max) : out;
  }

  private String byName(String name) {
    if (name.contains("email")) return faker.internet().emailAddress();
    if (name.contains("phone")) return "+15555550123";
    if (name.contains("firstname")) return faker.name().firstName();
    if (name.contains("lastname")) return faker.name().lastName();
    if (name.equals("name") || name.endsWith("name")) return faker.name().firstName();
    if (name.contains("city")) return faker.address().city();
    if (name.contains("country")) return "IN";
    if (name.contains("zip") || name.contains("postal")) return "560001";
    if (name.contains("url")) return "https://example.com/" + faker.lorem().word();
    return faker.lorem().word();
  }

  /** A value of the wrong JSON type for the schema. */
  public JsonNode wrongType(JsonNode s) {
    return switch (type(s)) {
      case "integer", "number", "boolean" -> F.textNode("not-a-" + type(s));
      case "array" -> F.textNode("not-an-array");
      case "object" -> F.textNode("not-an-object");
      default -> F.numberNode(12345);
    };
  }
}
