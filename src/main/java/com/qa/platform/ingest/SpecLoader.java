package com.qa.platform.ingest;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.List;

/** Loads Swagger 2.0 or OpenAPI 3.x, YAML or JSON, from a file path or an http(s) URL. Swagger 2 is converted to OpenAPI 3. */
public class SpecLoader {

  public record LoadResult(OpenAPI openApi, List<String> warnings) {}

  public LoadResult load(String pathOrUrl) {
    ParseOptions opts = new ParseOptions();
    opts.setResolve(true);
    opts.setResolveFully(true);         // inline every $ref so generators see whole schemas
    opts.setResolveCombinators(false);  // keep allOf / oneOf / anyOf as written
    opts.setValidateExternalRefs(true);

    SwaggerParseResult r = new OpenAPIParser().readLocation(pathOrUrl, null, opts);
    List<String> messages = r.getMessages() == null ? List.of() : r.getMessages();
    if (r.getOpenAPI() == null || r.getOpenAPI().getPaths() == null) {
      throw new SpecException("Cannot parse " + pathOrUrl + ": " + messages);
    }
    return new LoadResult(r.getOpenAPI(), messages);
  }

  public static class SpecException extends RuntimeException {
    public SpecException(String m) { super(m); }
  }
}
