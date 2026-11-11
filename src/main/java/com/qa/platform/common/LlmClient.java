package com.qa.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal LLM client for every AI task of the platform (com.qa.platform.ai: testcases, spec-audit, automation, rca),
 * no SDK. Off unless -Dllm.enabled=true / LLM_ENABLED=true
 * (the --llm flag and the UI checkbox set it) AND a token is present. Callers pass already-masked text and keep a
 * fallback (agent mode or a placeholder): every failure returns an empty Optional.
 *
 * <pre>
 * LLM_PROVIDER     endpoint                                                        token (first one set)
 * github-models    https://models.github.ai/inference/chat/completions             GITHUB_MODELS_TOKEN, GITHUB_TOKEN (models:read)
 *                  LLM_GITHUB_ORG=&lt;org&gt; bills the org: /orgs/&lt;org&gt;/inference/...
 * openai           LLM_BASE_URL + /chat/completions  (OpenAI-compatible: company   LLM_API_KEY, OPENAI_API_KEY
 *                  gateway, LiteLLM, vLLM, OpenAI). Default base https://api.openai.com/v1
 * azure-openai     LLM_BASE_URL/openai/deployments/LLM_DEPLOYMENT/chat/completions  LLM_API_KEY, AZURE_OPENAI_API_KEY
 *                  ?api-version=LLM_API_VERSION (default 2024-10-21), header api-key
 * anthropic        LLM_BASE_URL (default https://api.anthropic.com) + /v1/messages ANTHROPIC_API_KEY, LLM_API_KEY
 * agent | none     no HTTP call: AI tasks are answered by the IDE agent
 *                  (ai <task> --mode agent / --apply; Copilot agent mode, Claude Code)
 *
 * LLM_MODEL         model id (gateway/deployment specific). Defaults: openai/gpt-4.1, gpt-4.1, claude-sonnet-5-5
 * LLM_AUTH_HEADER   header for the token on openai, e.g. "api-key" or "X-Api-Key" (default: Authorization: Bearer)
 * LLM_PROXY         http://host:port for the LLM call only; else HTTPS_PROXY; else the JVM's https.proxyHost
 * </pre>
 * Company trust store for a gateway with an internal certificate: JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=..."
 */
public class LlmClient {

  /** Provider when LLM_PROVIDER is not set. */
  public static final String DEFAULT_PROVIDER = "github-models";

  private final String provider = cfg("llm.provider", "LLM_PROVIDER", DEFAULT_PROVIDER).toLowerCase(Locale.ROOT);
  private final boolean enabled;
  private final String model = cfg("llm.model", "LLM_MODEL", switch (provider) {
    case "anthropic" -> "claude-sonnet-5-5";
    case "openai", "azure-openai" -> "gpt-4.1";
    default -> "openai/gpt-4.1";
  });
  private final String token = switch (provider) {
    case "anthropic" -> first(System.getenv("ANTHROPIC_API_KEY"), System.getenv("LLM_API_KEY"));
    case "openai" -> first(System.getenv("LLM_API_KEY"), System.getenv("OPENAI_API_KEY"));
    case "azure-openai" -> first(System.getenv("LLM_API_KEY"), System.getenv("AZURE_OPENAI_API_KEY"));
    case "agent", "none" -> null;
    default -> first(System.getenv("GITHUB_MODELS_TOKEN"), System.getenv("GITHUB_TOKEN"));
  };
  private final HttpClient http = client();

  /** Enabled by -Dllm.enabled / LLM_ENABLED (the --llm flag and the UI checkbox set it). */
  public LlmClient() { this(Boolean.parseBoolean(cfg("llm.enabled", "LLM_ENABLED", "false"))); }

  /** {@code new LlmClient(true)}: the caller asked for the LLM explicitly (ai --mode llm). Still needs a token. */
  public LlmClient(boolean enabled) { this.enabled = enabled; }

  public boolean enabled() { return enabled && configured(); }

  /** A token for the provider is present (the run may still keep the LLM off). */
  public boolean configured() { return token != null && !token.isBlank(); }

  /** "agent": narratives come from the IDE agent (Copilot agent mode / Claude Code) instead of an HTTP call. */
  public boolean agentMode() { return "agent".equals(provider) || !configured(); }

  public String provider() { return provider; }

  public String model() { return provider + ":" + model; }

  /** The model's text answer, or empty when disabled or on any error. */
  public Optional<String> complete(String system, String user, int maxTokens) {
    if (!enabled()) return Optional.empty();
    try {
      HttpRequest req = switch (provider) {
        case "anthropic" -> anthropic(system, user, maxTokens);
        case "openai" -> chat(URI.create(stripSlash(cfg("llm.baseUrl", "LLM_BASE_URL", "https://api.openai.com/v1")) + "/chat/completions"),
            system, user, maxTokens, false);
        case "azure-openai" -> chat(azureUri(), system, user, maxTokens, true);
        default -> chat(githubUri(), system, user, maxTokens, false);
      };
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        System.err.println("[llm] " + provider + " HTTP " + resp.statusCode() + ": " + abbreviate(Masking.text(resp.body())));
        return Optional.empty();
      }
      JsonNode root = Json.MAPPER.readTree(resp.body());
      String text;
      if ("anthropic".equals(provider)) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : root.path("content")) if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        text = sb.toString();
      } else {
        text = root.path("choices").path(0).path("message").path("content").asText("");
      }
      return text.isBlank() ? Optional.empty() : Optional.of(text);
    } catch (Exception e) {
      System.err.println("[llm] call failed: " + e.getMessage());
      return Optional.empty();
    }
  }

  private URI githubUri() {
    String org = System.getenv("LLM_GITHUB_ORG");
    String base = stripSlash(cfg("llm.baseUrl", "LLM_BASE_URL", "https://models.github.ai"));
    return URI.create(base + (org == null || org.isBlank() ? "" : "/orgs/" + org) + "/inference/chat/completions");
  }

  private URI azureUri() {
    String base = stripSlash(cfg("llm.baseUrl", "LLM_BASE_URL", ""));
    if (base.isBlank()) throw new IllegalStateException("azure-openai needs LLM_BASE_URL=https://<resource>.openai.azure.com");
    String deployment = cfg("llm.deployment", "LLM_DEPLOYMENT", model);
    String version = cfg("llm.apiVersion", "LLM_API_VERSION", "2024-10-21");
    return URI.create(base + "/openai/deployments/" + deployment + "/chat/completions?api-version=" + version);
  }

  /** OpenAI-style chat completions (GitHub Models, OpenAI-compatible gateways, Azure OpenAI). */
  private HttpRequest chat(URI uri, String system, String user, int maxTokens, boolean azure) throws Exception {
    ObjectNode body = Json.MAPPER.createObjectNode();
    if (!azure) body.put("model", model);
    body.put("temperature", 0);
    body.put("max_tokens", maxTokens);
    ArrayNode messages = body.putArray("messages");
    messages.addObject().put("role", "system").put("content", system);
    messages.addObject().put("role", "user").put("content", user);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
    if ("github-models".equals(provider)) {
      b.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28");
    }
    String authHeader = azure ? "api-key" : cfg("llm.authHeader", "LLM_AUTH_HEADER", "Authorization");
    b.header(authHeader, "Authorization".equalsIgnoreCase(authHeader) ? "Bearer " + token : token);
    return b.build();
  }

  private HttpRequest anthropic(String system, String user, int maxTokens) throws Exception {
    String base = stripSlash(cfg("llm.baseUrl", "LLM_BASE_URL", "https://api.anthropic.com"));
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", maxTokens);
    body.put("temperature", 0);
    body.put("system", system);
    body.putArray("messages").addObject().put("role", "user").put("content", user);
    return HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
        .timeout(Duration.ofSeconds(90))
        .header("content-type", "application/json")
        .header("x-api-key", token)
        .header("anthropic-version", "2023-06-01")
        .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
        .build();
  }

  /** LLM_PROXY, else HTTPS_PROXY (as curl and most CLIs use it), else the JVM default proxy selector. */
  private static HttpClient client() {
    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
    String proxy = first(System.getenv("LLM_PROXY"), first(System.getenv("HTTPS_PROXY"), System.getenv("https_proxy")));
    if (proxy != null && !proxy.isBlank()) {
      try {
        URI p = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
        b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort() > 0 ? p.getPort() : 8080)));
      } catch (IllegalArgumentException e) {
        System.err.println("[llm] ignoring unreadable proxy setting: " + e.getMessage());
      }
    }
    return b.build();
  }

  /** Pulls the first JSON object/array out of a model answer (tolerates ```json fences). */
  public static Optional<JsonNode> extractJson(String text) {
    if (text == null) return Optional.empty();
    String t = text.replaceAll("(?s)```(?:json|yaml)?", "").trim();
    int i = t.indexOf('{'), j = t.indexOf('[');
    int start = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    if (start < 0) return Optional.empty();
    char close = t.charAt(start) == '{' ? '}' : ']';
    int end = t.lastIndexOf(close);
    if (end <= start) return Optional.empty();
    try {
      return Optional.of(Json.MAPPER.readTree(t.substring(start, end + 1)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static String cfg(String prop, String env, String def) {
    String v = System.getProperty(prop);
    if (v == null || v.isBlank()) v = System.getenv(env);
    return v == null || v.isBlank() ? def : v;
  }

  private static String first(String a, String b) { return a != null && !a.isBlank() ? a : b; }

  private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

  private static String abbreviate(String s) { return s == null ? "" : s.length() > 500 ? s.substring(0, 500) + "..." : s; }
}
