package com.qa.platform.ingest.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.LlmClient;
import com.qa.platform.common.PromptLibrary;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Hand-written API docs (Markdown, or HTML exported from Confluence/Notion) -> OpenAPI 3.
 * Rules first; sections the rules cannot read are sent to the LLM (prompts/doc-to-openapi.md) when enabled.
 */
public class MarkdownAdapter implements DocAdapter {

  private static final Pattern ENDPOINT = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/[^\\s`'\"]*)");
  private static final Pattern STATUS = Pattern.compile("\\b([1-5]\\d\\d)\\b");

  private final LlmClient llm;

  public MarkdownAdapter() { this(new LlmClient()); }

  public MarkdownAdapter(LlmClient llm) { this.llm = llm; }

  @Override public String name() { return "markdown"; }

  @Override
  public boolean supports(Path f) {
    String n = f.toString().toLowerCase(Locale.ROOT);
    return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".html") || n.endsWith(".htm");
  }

  @Override
  public Result toOpenApi(Path f) throws Exception {
    String text = Files.readString(f);
    String html = f.toString().toLowerCase(Locale.ROOT).endsWith(".md") || f.toString().toLowerCase(Locale.ROOT).endsWith(".markdown")
        ? HtmlRenderer.builder().build().render(Parser.builder().build().parse(text))
        : text;
    Document doc = Jsoup.parse(html);
    String title = doc.select("h1").isEmpty() ? f.getFileName().toString() : doc.select("h1").first().text();

    OpenAPI api = new OpenAPI().info(new Info().title(title).version("1.0.0")).paths(new Paths());
    List<String> warnings = new ArrayList<>();
    List<String> unread = new ArrayList<>();

    Operation current = null;
    String label = "";
    StringBuilder section = new StringBuilder();
    String currentTag = "default";

    for (Element el : doc.body().children()) {
      String t = el.text();
      Matcher m = ENDPOINT.matcher(t);
      boolean isHeading = el.tagName().matches("h[1-6]");

      if (m.find() && (isHeading || el.tagName().equals("p") || el.tagName().equals("pre"))) {
        flushUnread(current, section, unread);
        String method = m.group(1);
        String path = m.group(2).replaceAll(":(\\w+)", "{$1}");
        current = new Operation()
            .operationId(PostmanAdapter.camel(method.toLowerCase(Locale.ROOT) + " " + path))
            .summary(isHeading ? t : method + " " + path)
            .addTagsItem(currentTag)
            .responses(new ApiResponses());
        for (String seg : path.split("/")) {
          if (seg.startsWith("{")) {
            current.addParametersItem(new Parameter().in("path").name(seg.replaceAll("[{}]", "")).required(true).schema(new StringSchema()));
          }
        }
        api.getPaths().computeIfAbsent(path, k -> new PathItem()).operation(PathItem.HttpMethod.valueOf(method), current);
        section.setLength(0);
        label = "";
        continue;
      }
      if (isHeading && el.tagName().equals("h2")) currentTag = PostmanAdapter.camel(t);
      if (current == null) continue;
      section.append(t).append('\n');

      String lower = t.toLowerCase(Locale.ROOT);
      if (!el.tagName().equals("pre") && (lower.contains("request") || lower.contains("response") || lower.contains("body"))) {
        label = lower;
      }

      if (el.tagName().equals("table")) {
        readParams(el, current);
      } else if (el.tagName().equals("pre")) {
        JsonNode example = json(el.text());
        if (example == null) continue;
        if (label.contains("response")) {
          Matcher s = STATUS.matcher(label);
          String code = s.find() ? s.group(1) : "200";
          current.getResponses().addApiResponse(code, new ApiResponse().description("From doc")
              .content(new Content().addMediaType("application/json", new MediaType().schema(SchemaInferer.infer(example)))));
        } else {
          current.requestBody(new RequestBody().required(true).content(new Content()
              .addMediaType("application/json", new MediaType().schema(SchemaInferer.infer(example)))));
        }
      }
    }
    flushUnread(current, section, unread);

    api.getPaths().forEach((p, item) -> item.readOperations().forEach(op -> {
      if (op.getResponses().isEmpty()) {
        op.getResponses().addApiResponse("200", new ApiResponse().description("Assumed: doc lists no response"));
        warnings.add(op.getOperationId() + ": no response example in the doc, assumed 200");
      }
    }));

    if (api.getPaths().isEmpty() || !unread.isEmpty()) {
      llmFallback(text, api, warnings);
    }
    if (api.getPaths().isEmpty()) warnings.add("No endpoints found. Use lines like 'POST /orders' in headings.");
    return new Result(api, warnings);
  }

  /** Parameter table: columns Name | In | Type | Required | Description (In and Required optional). */
  private static void readParams(Element table, Operation op) {
    Elements headers = table.select("thead th, tr:first-child th");
    List<String> cols = headers.stream().map(h -> h.text().trim().toLowerCase(Locale.ROOT)).toList();
    int name = cols.indexOf("name"), in = cols.indexOf("in"), type = cols.indexOf("type"), req = cols.indexOf("required");
    if (name < 0) name = cols.indexOf("parameter");
    if (name < 0 || type < 0) return;
    for (Element row : table.select("tbody tr")) {
      Elements c = row.select("td");
      if (c.size() <= Math.max(name, type)) continue;
      String location = in >= 0 && c.size() > in ? c.get(in).text().toLowerCase(Locale.ROOT) : "query";
      if (location.equals("body")) continue;
      boolean required = req >= 0 && c.size() > req && c.get(req).text().toLowerCase(Locale.ROOT).matches("yes|true|required|y");
      Schema<?> s = c.get(type).text().toLowerCase(Locale.ROOT).startsWith("int") ? new IntegerSchema() : new StringSchema();
      String pname = c.get(name).text().replace("`", "").trim();
      boolean exists = op.getParameters() != null && op.getParameters().stream().anyMatch(p -> p.getName().equals(pname));
      if (!exists) op.addParametersItem(new Parameter().in(location).name(pname).required(required || location.equals("path")).schema(s));
    }
  }

  private static void flushUnread(Operation op, StringBuilder section, List<String> unread) {
    if (op != null && op.getRequestBody() == null && op.getParameters() == null && section.length() > 200) {
      unread.add(section.toString());
    }
  }

  private void llmFallback(String fullDoc, OpenAPI api, List<String> warnings) {
    if (!llm.enabled()) {
      warnings.add("Some sections could not be read by the rules. Convert the whole doc with AI: "
          + "java -jar target/api-test-platform.jar ai doc-to-openapi --doc <file> (LLM token, or your IDE agent with --mode agent), "
          + "or enable the LLM fallback here with -Dllm.enabled=true.");
      return;
    }
    PromptLibrary.Prompt p = PromptLibrary.load("doc-to-openapi", Map.of("api_doc", fullDoc));
    llm.complete(p.system(), p.user(), 8000).ifPresent(answer -> {
      String yaml = answer.replaceAll("(?s)^.*?```(?:yaml)?\\s*", "").replaceAll("(?s)```.*$", "");
      var parsed = new OpenAPIParser().readContents(yaml, null, null);
      if (parsed.getOpenAPI() == null || parsed.getOpenAPI().getPaths() == null) {
        warnings.add("LLM answer was not valid OpenAPI; ignored");
        return;
      }
      parsed.getOpenAPI().getPaths().forEach((path, item) -> {
        if (!api.getPaths().containsKey(path)) {
          api.getPaths().addPathItem(path, item);
          warnings.add("LLM added " + path + " (check it in normalized-openapi.yaml)");
        }
      });
    });
  }

  private static JsonNode json(String s) {
    try {
      String t = s.trim();
      return t.startsWith("{") || t.startsWith("[") ? Json.MAPPER.readTree(t) : null;
    } catch (Exception e) {
      return null;
    }
  }
}
