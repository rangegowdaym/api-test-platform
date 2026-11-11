package com.qa.automation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.automation.client.auth.AuthProviders;
import com.qa.platform.common.Json;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.rg.reporter.ReportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ONLY class that talks to Rest Assured for API calls. Everything above it (Api classes, services, steps)
 * works with {@link ApiRequest} and {@link ApiResponse}.
 *
 * <ul>
 *   <li>base URI per service from config, timeouts and charset handling set once</li>
 *   <li>auth applied through {@link com.qa.automation.client.auth.AuthProvider} strategies</li>
 *   <li>bodies serialised with the platform's Jackson mapper (records, JsonNode, String)</li>
 *   <li>every exchange is masked and handed to an {@link ExchangeRecorder} (evidence for RCA)</li>
 *   <li>transport failures become {@link ApiClientException} with the cause preserved</li>
 * </ul>
 * One instance per scenario (PicoContainer injects it), so it is never shared between threads.
 */
public class ApiClient {

  private static final Logger LOG = LoggerFactory.getLogger(ApiClient.class);

  private final ClientConfig config;
  private final RestAssuredConfig restAssuredConfig;
  private final CaptureFilter capture;

  /** PicoContainer constructor: records exchanges into the scenario's context. */
  public ApiClient(com.qa.automation.context.ScenarioContext ctx) {
    this(ClientConfig.fromTestConfig(), ctx);
  }

  /** Outside Cucumber (unit tests, tools): {@code ApiClient.create(config, recorder)}. */
  public static ApiClient create(ClientConfig config, ExchangeRecorder recorder) { return new ApiClient(config, recorder); }

  // not public on purpose: PicoContainer must see exactly one public constructor
  protected ApiClient(ClientConfig config, ExchangeRecorder recorder) {
    this.config = config;
    this.capture = new CaptureFilter(recorder == null ? ExchangeRecorder.NONE : recorder);
    this.restAssuredConfig = config.restAssured();
  }

  public ApiResponse send(ApiRequest request) {
    String baseUri = config.baseUri(request.service());
    RequestSpecification spec = RestAssured.given()
        .config(restAssuredConfig)
        .baseUri(baseUri)
        .filter(capture)
        .urlEncodingEnabled(true);
    config.applyNetwork(spec, baseUri, request.headers().keySet());   // company proxy + default headers the case does not set

    if (!request.pathParams().isEmpty()) spec.pathParams(request.pathParams());
    if (!request.queryParams().isEmpty()) spec.queryParams(request.queryParams());
    request.headers().forEach((name, value) -> spec.header(name, value));

    if (request.body() != null) {
      spec.body(serialise(request.body()));
      if (request.contentType() != null) spec.contentType(request.contentType());
      else if (!hasHeader(request, "Content-Type")) spec.contentType("application/json");
    }
    AuthProviders.forProfile(request.auth()).apply(spec);

    // One "action" step per HTTP call in the report's Steps tab (a no-op when the report plugin is not active).
    return ReportContext.action(request.describe(), () -> execute(spec, request));
  }

  private ApiResponse execute(RequestSpecification spec, ApiRequest request) {
    long start = System.nanoTime();
    try {
      Response response = spec.request(request.method().toRestAssured(), request.path());
      long ms = (System.nanoTime() - start) / 1_000_000;
      ApiResponse result = new ApiResponse(request, response, ms);
      if (config.logExchanges()) LOG.info("{}", result);
      else LOG.debug("{}", result);
      ReportContext.log(request.describe() + " -> " + result.status() + " (" + ms + " ms)");
      return result;
    } catch (Exception e) {   // Rest Assured rethrows checked IOExceptions (ConnectException...) unchecked
      ReportContext.log(request.describe() + " -> " + summary(e));
      throw new ApiClientException("Request failed: " + request.describe() + " -> " + summary(e), e);
    }
  }

  private static String serialise(Object body) {
    if (body instanceof String s) return s;
    if (body instanceof JsonNode n) return n.toString();
    try {
      return Json.MAPPER.writeValueAsString(body);
    } catch (Exception e) {
      throw new ApiClientException("Cannot serialise request body of type " + body.getClass().getName(), e);
    }
  }

  private static boolean hasHeader(ApiRequest r, String name) {
    return r.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase(name));
  }

  /** "java.net.ConnectException: Connection refused" from the deepest cause. */
  private static String summary(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    return root.getClass().getName() + ": " + root.getMessage();
  }
}
