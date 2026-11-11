package com.qa.platform.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.qa.platform.common.Hashing;
import com.qa.platform.model.ApiModel;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.Param;
import com.qa.platform.model.ResponseSpec;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** swagger-parser model -> ApiModel. The only class (besides adapters) that knows swagger types. */
public class ApiModelMapper {

  private final ObjectMapper swaggerJson = Json.mapper();   // has the mixins that serialise Schema correctly

  public ApiModel map(OpenAPI api, String specName) {
    List<Endpoint> endpoints = new ArrayList<>();
    List<SecurityRequirement> globalSecurity = api.getSecurity() == null ? List.of() : api.getSecurity();

    api.getPaths().forEach((path, item) ->
        item.readOperationsMap().forEach((method, op) ->
            endpoints.add(toEndpoint(path, item, method, op, globalSecurity))));

    Map<String, JsonNode> schemes = new LinkedHashMap<>();
    if (api.getComponents() != null && api.getComponents().getSecuritySchemes() != null) {
      api.getComponents().getSecuritySchemes().forEach((k, v) -> schemes.put(k, tree(v)));
    }
    List<String> servers = api.getServers() == null ? List.of()
        : api.getServers().stream().map(Server::getUrl).toList();

    String hash = Hashing.sha256(Json.pretty(api));
    return new ApiModel(api.getInfo() == null ? specName : api.getInfo().getTitle(),
        api.getInfo() == null ? "" : api.getInfo().getVersion(),
        specName, hash, servers, endpoints, schemes);
  }

  private Endpoint toEndpoint(String path, PathItem item, PathItem.HttpMethod method, Operation op,
                              List<SecurityRequirement> globalSecurity) {
    // path-level parameters first, operation-level ones override by (name, in)
    Map<String, Param> params = new LinkedHashMap<>();
    List<Parameter> all = new ArrayList<>();
    if (item.getParameters() != null) all.addAll(item.getParameters());
    if (op.getParameters() != null) all.addAll(op.getParameters());
    for (Parameter p : all) {
      boolean required = Boolean.TRUE.equals(p.getRequired()) || "path".equals(p.getIn());
      params.put(p.getIn() + ":" + p.getName(),
          new Param(p.getName(), p.getIn(), required, tree(p.getSchema()), p.getExample()));
    }

    JsonNode body = null;
    boolean bodyRequired = false;
    if (op.getRequestBody() != null) {
      MediaType mt = jsonMediaType(op.getRequestBody().getContent());
      if (mt != null) body = tree(mt.getSchema());
      bodyRequired = Boolean.TRUE.equals(op.getRequestBody().getRequired());
    }

    Map<String, ResponseSpec> responses = new LinkedHashMap<>();
    if (op.getResponses() != null) {
      op.getResponses().forEach((code, resp) -> {
        MediaType mt = jsonMediaType(resp.getContent());
        List<String> headers = resp.getHeaders() == null ? List.of() : new ArrayList<>(resp.getHeaders().keySet());
        responses.put(code, new ResponseSpec(resp.getDescription(), mt == null ? null : tree(mt.getSchema()), headers));
      });
    }

    List<SecurityRequirement> sec = op.getSecurity() != null ? op.getSecurity() : globalSecurity;
    List<String> security = sec.stream().flatMap(s -> s.keySet().stream()).distinct().toList();

    String opId = op.getOperationId() != null && !op.getOperationId().isBlank()
        ? op.getOperationId()
        : method.name().toLowerCase() + path.replaceAll("[^A-Za-z0-9]+", "_");

    return new Endpoint(method.name(), path, opId, op.getSummary(),
        op.getTags() == null || op.getTags().isEmpty() ? List.of("default") : op.getTags(),
        new ArrayList<>(params.values()), body, bodyRequired, responses, security);
  }

  private static MediaType jsonMediaType(Content content) {
    if (content == null || content.isEmpty()) return null;
    if (content.containsKey("application/json")) return content.get("application/json");
    return content.entrySet().stream()
        .filter(e -> e.getKey().contains("json"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(content.values().iterator().next());
  }

  /** Recursive schemas resolved "fully" can be cyclic; fall back to a plain object instead of overflowing. */
  private JsonNode tree(Object swaggerObject) {
    if (swaggerObject == null) return null;
    try {
      return swaggerJson.valueToTree(swaggerObject);
    } catch (StackOverflowError | IllegalArgumentException e) {
      return JsonNodeFactory.instance.objectNode().put("type", "object").put("x-recursive", true);
    }
  }
}
