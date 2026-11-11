package com.qa.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Endpoint(String method, String path, String operationId, String summary,
                       List<String> tags, List<Param> params,
                       JsonNode requestBodySchema, boolean bodyRequired,
                       Map<String, ResponseSpec> responses,
                       List<String> security) {

  public String primaryTag() { return tags == null || tags.isEmpty() ? "default" : tags.get(0); }

  /** First documented 2xx code, or 200. */
  public String successCode() {
    return responses.keySet().stream().filter(c -> c.startsWith("2")).sorted()
        .map(c -> c.toUpperCase().replace("XX", "00")).findFirst().orElse("200");
  }

  /** First documented code in the given family, e.g. "4" -> "400" or "422". */
  public Optional<String> documented(String... preferred) {
    for (String p : preferred) if (responses.containsKey(p)) return Optional.of(p);
    return Optional.empty();
  }

  public List<Param> params(String in) {
    return params.stream().filter(p -> in.equals(p.in())).toList();
  }

  public boolean secured() { return security != null && !security.isEmpty(); }
}
