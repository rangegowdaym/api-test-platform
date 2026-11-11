package com.qa.platform.ingest;

import com.qa.platform.common.Json;
import com.qa.platform.ingest.adapter.DocAdapter;
import com.qa.platform.ingest.adapter.MarkdownAdapter;
import com.qa.platform.ingest.adapter.PostmanAdapter;
import com.qa.platform.model.ApiModel;
import io.swagger.v3.core.util.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single entry point for every input type:
 *   Swagger 2 / OpenAPI 3 (YAML|JSON, file|URL)  -> parsed directly
 *   Postman v2.1 collection, Markdown/HTML doc   -> adapter -> normalized-openapi.yaml -> parsed
 */
public class IngestService {

  private final List<DocAdapter> adapters;

  public IngestService() { this(List.of(new PostmanAdapter(), new MarkdownAdapter())); }

  public IngestService(List<DocAdapter> adapters) { this.adapters = adapters; }

  public ApiModel ingest(String input, Path outDir) throws Exception {
    Files.createDirectories(outDir);
    String specPath = input;
    List<String> warnings = new ArrayList<>();
    String inputType = "openapi";

    Path p = isUrl(input) ? null : Path.of(input);
    if (p != null && Files.exists(p)) {
      for (DocAdapter a : adapters) {
        if (a.supports(p)) {
          DocAdapter.Result r = a.toOpenApi(p);
          Path normalized = outDir.resolve("normalized-openapi.yaml");
          Files.writeString(normalized, Yaml.pretty(r.openApi()));
          specPath = normalized.toString();
          warnings.addAll(r.warnings());
          inputType = a.name();
          break;
        }
      }
    } else if (!isUrl(input)) {
      throw new SpecLoader.SpecException("Input not found: " + input);
    }

    SpecLoader.LoadResult loaded = new SpecLoader().load(specPath);
    warnings.addAll(loaded.warnings());
    String specName = isUrl(input) ? input : Path.of(input).getFileName().toString();
    ApiModel model = new ApiModelMapper().map(loaded.openApi(), specName);

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("input", input);
    report.put("inputType", inputType);
    report.put("parsedAs", specPath);
    report.put("title", model.title());
    report.put("version", model.version());
    report.put("specHash", model.specHash());
    report.put("endpoints", model.endpoints().size());
    report.put("warnings", warnings);
    report.put("ingestedAt", Instant.now().toString());
    Json.write(outDir.resolve("ingest-report.json"), report);
    return model;
  }

  private static boolean isUrl(String s) { return s.startsWith("http://") || s.startsWith("https://"); }
}
