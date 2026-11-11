package com.qa.platform.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** auth = name of an auth profile in config/env/<env>.properties, "none", or "invalid". */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CaseRequest(Map<String, Object> pathParams, Map<String, Object> queryParams,
                          Map<String, String> headers, String auth, JsonNode body) {}
