package com.qa.platform.ingest.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Postman collection v2.0 / v2.1 -> OpenAPI 3. Folders become tags, saved example responses become responses. */
public class PostmanAdapter implements DocAdapter {

  private static final Set<String> SKIP_HEADERS = Set.of("content-type", "accept", "authorization", "user-agent");

  @Override public String name() { return "postman"; }

  @Override
  public boolean supports(Path f) {
    if (!f.toString().toLowerCase(Locale.ROOT).endsWith(".json")) return false;
    try {
      JsonNode info = Json.MAPPER.readTree(f.toFile()).path("info");
      return info.has("_postman_id") || info.path("schema").asText().contains("getpostman.com");
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public Result toOpenApi(Path f) throws Exception {
    JsonNode root = Json.MAPPER.readTree(Files.readString(f));
    OpenAPI api = new OpenAPI()
        .info(new Info().title(root.path("info").path("name").asText("Postman collection")).version("1.0.0"))
        .paths(new Paths());
    List<String> warnings = new ArrayList<>();
    walk(root.path("item"), api, "default", warnings, new HashSet<>());
    return new Result(api, warnings);
  }

  private void walk(JsonNode items, OpenAPI api, String tag, List<String> warnings, Set<String> usedIds) {
    for (JsonNode it : items) {
      if (it.has("item")) {                       // a folder
        walk(it.get("item"), api, it.path("name").asText(tag), warnings, usedIds);
        continue;
      }
      JsonNode req = it.path("request");
      String method = req.path("method").asText("GET").toUpperCase(Locale.ROOT);
      String path = path(req.path("url"));
      if (path == null) {
        warnings.add("Skipped '" + it.path("name").asText() + "': no URL");
        continue;
      }

      String opId = uniqueId(camel(it.path("name").asText(method + path)), usedIds);
      Operation op = new Operation().operationId(opId).summary(it.path("name").asText()).addTagsItem(tag);

      for (String seg : path.split("/")) {
        if (seg.startsWith("{") && seg.endsWith("}")) {
          op.addParametersItem(new PathParameter().name(seg.substring(1, seg.length() - 1)).required(true).schema(new StringSchema()));
        }
      }
      for (JsonNode q : req.path("url").path("query")) {
        if (q.path("disabled").asBoolean(false)) continue;
        StringSchema s = new StringSchema();
        s.setExample(q.path("value").asText());
        op.addParametersItem(new QueryParameter().name(q.path("key").asText()).required(false).schema(s));
      }
      for (JsonNode h : req.path("header")) {
        String key = h.path("key").asText();
        if (SKIP_HEADERS.contains(key.toLowerCase(Locale.ROOT))) continue;
        op.addParametersItem(new HeaderParameter().name(key).required(true).schema(new StringSchema()));
      }

      String raw = req.path("body").path("raw").asText("");
      if (!raw.isBlank()) {
        JsonNode example = readJsonOrNull(raw.replaceAll("\\{\\{[^}]+}}", "\"var\""));
        if (example != null) {
          op.requestBody(new RequestBody().required(true).content(new Content()
              .addMediaType("application/json", new MediaType().schema(SchemaInferer.infer(example)))));
        } else {
          warnings.add(opId + ": request body is not JSON, left out");
        }
      }

      ApiResponses responses = new ApiResponses();
      for (JsonNode ex : it.path("response")) {
        String code = ex.path("code").asText("200");
        ApiResponse r = new ApiResponse().description(ex.path("name").asText(ex.path("status").asText("Example")));
        JsonNode body = readJsonOrNull(ex.path("body").asText(""));
        if (body != null) {
          r.content(new Content().addMediaType("application/json", new MediaType().schema(SchemaInferer.infer(body))));
        }
        responses.addApiResponse(code, r);
      }
      if (responses.isEmpty()) {
        responses.addApiResponse("200", new ApiResponse().description("OK (no saved example in Postman)"));
        warnings.add(opId + ": no saved example response, assumed 200 with no schema");
      }
      op.responses(responses);

      PathItem pi = api.getPaths().computeIfAbsent(path, k -> new PathItem());
      pi.operation(PathItem.HttpMethod.valueOf(method), op);
    }
  }

  /** "{{baseUrl}}/orders/:orderId" or {path:[..]} -> "/orders/{orderId}" */
  private static String path(JsonNode url) {
    List<String> segs = new ArrayList<>();
    if (url.isTextual()) {
      String s = url.asText().replaceAll("^\\{\\{[^}]+}}", "").replaceAll("^https?://[^/]+", "");
      s = s.split("\\?")[0];
      for (String p : s.split("/")) if (!p.isBlank()) segs.add(p);
    } else if (url.has("path")) {
      for (JsonNode p : url.path("path")) segs.add(p.asText());
    } else {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (String seg : segs) {
      String s = seg.startsWith(":") ? "{" + seg.substring(1) + "}" : seg.replaceAll("\\{\\{([^}]+)}}", "{$1}");
      sb.append('/').append(s);
    }
    return sb.length() == 0 ? "/" : sb.toString();
  }

  private static JsonNode readJsonOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Json.MAPPER.readTree(s);
    } catch (Exception e) {
      return null;
    }
  }

  static String camel(String s) {
    String[] parts = s.replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String p = parts[i].toLowerCase(Locale.ROOT);
      if (p.isEmpty()) continue;
      sb.append(i == 0 ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
    }
    return sb.length() == 0 ? "operation" : sb.toString();
  }

  private static String uniqueId(String id, Set<String> used) {
    String candidate = id;
    int n = 2;
    while (!used.add(candidate)) candidate = id + n++;
    return candidate;
  }
}
