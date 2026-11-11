package com.qa.platform.ingest.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds a JSON schema from an example JSON value (used for Postman and Markdown docs that have no schema). */
public final class SchemaInferer {
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F-]{36}$");
  private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T.*");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  private SchemaInferer() {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Schema infer(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return new Schema<>().nullable(true);
    if (n.isObject()) {
      ObjectSchema s = new ObjectSchema();
      Iterator<Map.Entry<String, JsonNode>> it = n.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> f = it.next();
        s.addProperty(f.getKey(), infer(f.getValue()));
      }
      // every field seen in the example is assumed required; the reviewer relaxes this in the approval UI
      if (s.getProperties() != null) s.setRequired(new ArrayList<>(s.getProperties().keySet()));
      return s;
    }
    if (n.isArray()) return new ArraySchema().items(n.size() > 0 ? infer(n.get(0)) : new ObjectSchema());
    if (n.isIntegralNumber()) return new IntegerSchema().example(n.asLong());
    if (n.isNumber()) return new NumberSchema().example(n.decimalValue());
    if (n.isBoolean()) return new BooleanSchema().example(n.asBoolean());
    String v = n.asText();
    StringSchema s = new StringSchema();
    s.setExample(v);
    if (EMAIL.matcher(v).matches()) s.setFormat("email");
    else if (UUID.matcher(v).matches()) s.setFormat("uuid");
    else if (DATE_TIME.matcher(v).matches()) s.setFormat("date-time");
    else if (DATE.matcher(v).matches()) s.setFormat("date");
    return s;
  }
}
