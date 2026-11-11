package com.qa.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ResponseSpec(String description, JsonNode schema, List<String> headers) {}
