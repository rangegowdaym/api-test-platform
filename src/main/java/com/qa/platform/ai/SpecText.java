package com.qa.platform.ai;

import com.qa.platform.common.Workspace;
import com.qa.platform.ingest.IngestService;
import com.qa.platform.model.ApiModel;
import java.nio.file.Files;
import java.nio.file.Path;

/** The spec as text for a prompt: the file itself for OpenAPI/Swagger, the normalised OpenAPI for other inputs. */
final class SpecText {
  private SpecText() {}

  record Spec(ApiModel model, String text) {}

  static Spec load(String spec, int maxChars) throws Exception {
    Path out = Workspace.OUT.resolve("ai-ingest");
    ApiModel model = new IngestService().ingest(spec, out);
    Path normalized = out.resolve("normalized-openapi.yaml");
    String text;
    Path file = Path.of(spec);
    if (Files.exists(normalized)) text = Files.readString(normalized);
    else if (Files.exists(file)) text = Files.readString(file);
    else text = "(spec " + spec + " could not be read as text)";
    return new Spec(model, AiText.clean(text, maxChars));
  }
}
