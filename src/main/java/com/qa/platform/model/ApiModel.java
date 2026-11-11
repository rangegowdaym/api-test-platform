package com.qa.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Spec-independent view of an API. Everything after ingest works on this, never on swagger-parser types. */
public record ApiModel(String title, String version, String specName, String specHash,
                       List<String> servers, List<Endpoint> endpoints, Map<String, JsonNode> securitySchemes) {}
