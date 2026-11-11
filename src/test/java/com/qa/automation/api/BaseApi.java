package com.qa.automation.api;

import com.qa.automation.client.ApiClient;
import com.qa.automation.client.ApiRequest;
import com.qa.automation.client.ApiResponse;
import com.qa.automation.client.HttpMethod;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Base for endpoint classes: one subclass per OpenAPI tag, one method per operation, returning the raw
 * {@link ApiResponse} with no assertions. Subclasses in {@code api.generated} are written by
 * {@code java -jar target/api-test-platform.jar services --spec <spec>}; do not edit them by hand.
 * Business logic and assertions belong in {@code services}, not here.
 */
public abstract class BaseApi {

  /** Pass to any operation method to adjust a single call (extra header, different auth, ...). */
  public static final Consumer<ApiRequest.Builder> NO_CHANGES = b -> { };

  private final ApiClient client;
  private final String service;
  private final String authProfile;

  protected BaseApi(ApiClient client, String service, String authProfile) {
    this.client = Objects.requireNonNull(client, "client");
    this.service = service;
    this.authProfile = authProfile == null ? "default" : authProfile;
  }

  /** Starts a request for this API's service (base URI) and auth profile. */
  protected final ApiRequest.Builder request(HttpMethod method, String path) {
    return ApiRequest.builder(method, path).service(service).auth(authProfile);
  }

  protected final ApiResponse send(ApiRequest.Builder request, Consumer<ApiRequest.Builder> customize) {
    (customize == null ? NO_CHANGES : customize).accept(request);
    return client.send(request.build());
  }

  public final String service() { return service; }
  public final String authProfile() { return authProfile; }
}
