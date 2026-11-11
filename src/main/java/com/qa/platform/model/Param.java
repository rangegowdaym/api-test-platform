package com.qa.platform.model;

import com.fasterxml.jackson.databind.JsonNode;

/** in = path | query | header | cookie */
public record Param(String name, String in, boolean required, JsonNode schema, Object example) {}
