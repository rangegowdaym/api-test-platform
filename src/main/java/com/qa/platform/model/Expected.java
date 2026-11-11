package com.qa.platform.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Expected(int status, JsonNode schema, List<Assertion> assertions, Long maxResponseTimeMs) {

  /** op = equals | notNull | contains | matches | size */
  public record Assertion(String path, String op, Object value) {}
}
