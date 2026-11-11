package com.qa.platform.ingest.adapter;

import io.swagger.v3.oas.models.OpenAPI;
import java.nio.file.Path;
import java.util.List;

/** Turns a non-OpenAPI API description into OpenAPI 3, so the rest of the pipeline only ever sees OpenAPI. */
public interface DocAdapter {

  record Result(OpenAPI openApi, List<String> warnings) {}

  String name();

  boolean supports(Path file);

  Result toOpenApi(Path file) throws Exception;
}
