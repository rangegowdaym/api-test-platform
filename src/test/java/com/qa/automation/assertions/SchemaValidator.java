package com.qa.automation.assertions;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates JSON against OpenAPI 3.0 schemas (nullable supported). Compiled schemas are cached. */
public final class SchemaValidator {
  private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

  private SchemaValidator() {}

  /** Empty list = valid. */
  public static List<String> validate(JsonNode schema, JsonNode instance) {
    JsonSchema compiled = CACHE.computeIfAbsent(schema.toString(), k -> {
      SchemaValidatorsConfig cfg = new SchemaValidatorsConfig();
      cfg.setHandleNullableField(true);
      cfg.setTypeLoose(false);
      return FACTORY.getSchema(schema, cfg);
    });
    return compiled.validate(instance).stream().map(ValidationMessage::getMessage).toList();
  }
}
