package com.qa.platform.ai;

import com.qa.platform.common.Workspace;
import io.swagger.parser.OpenAPIParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** API documentation (Markdown, HTML, text) -> OpenAPI 3 (prompts/doc-to-openapi.md) -> specs/<doc>.openapi.yaml. */
final class DocToOpenApiTask implements AiTask {
  private static final String HEADER = "# GENERATED from ";
  private final String doc;

  DocToOpenApiTask(String doc) {
    if (doc == null || doc.isBlank()) throw new IllegalArgumentException("doc-to-openapi needs --doc");
    this.doc = doc;
  }

  public String name() { return "doc-to-openapi"; }
  public String format() { return "yaml"; }
  public Map<String, String> args() { return Map.of("doc", doc); }
  public int maxTokens() { return 12000; }
  public String describe() {
    return "Turn the API documentation " + doc + " into one OpenAPI 3 YAML document -> " + target() + ". "
        + "A person reviews it before cases are generated from it.";
  }

  private Path target() {
    String base = Path.of(doc).getFileName().toString().replaceAll("\\.[^.]+$", "");
    return Workspace.SPECS.resolve(AiText.safeId(base) + ".openapi.yaml");
  }

  public List<AiRequest> requests() throws Exception {
    Path f = Path.of(doc);
    if (!Files.exists(f)) throw new IllegalArgumentException("No such file: " + doc);
    return List.of(new AiRequest(f.getFileName().toString(), "doc-to-openapi", Map.of("api_doc", AiText.clean(Files.readString(f), 120000))));
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    String yaml = AiText.unfence(answer, "yaml");
    var parsed = new OpenAPIParser().readContents(yaml, null, null);
    if (parsed.getOpenAPI() == null || parsed.getOpenAPI().getPaths() == null || parsed.getOpenAPI().getPaths().isEmpty()) {
      throw new IllegalArgumentException("not a valid OpenAPI document with paths: " + parsed.getMessages());
    }
    Path out = target();
    if (Files.exists(out) && !Files.readString(out).startsWith(HEADER)) {
      throw new IllegalArgumentException(out + " exists and was not generated: move it away first");
    }
    Files.createDirectories(out.getParent());
    Files.writeString(out, HEADER + doc + " by " + by + " (prompts/doc-to-openapi.md). Review before use.\n" + yaml.strip() + "\n");
    return parsed.getOpenAPI().getPaths().size() + " paths -> " + out
        + (parsed.getMessages() == null || parsed.getMessages().isEmpty() ? "" : " (parser notes: " + parsed.getMessages() + ")")
        + ". Next: java -jar target/api-test-platform.jar generate --spec " + Workspace.ROOT.relativize(out);
  }
}
