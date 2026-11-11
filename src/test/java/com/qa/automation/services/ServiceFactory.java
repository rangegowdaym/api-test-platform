package com.qa.automation.services;

import com.qa.automation.api.BaseApi;
import com.qa.automation.client.ApiClient;
import com.qa.automation.context.ScenarioContext;
import java.lang.reflect.Constructor;

/**
 * The one entry point steps use to reach the API. PicoContainer injects it per scenario, so every service and
 * endpoint class shares the scenario's {@link ApiClient} (and therefore its evidence capture).
 *
 * <pre>{@code
 * // any generated endpoint class, no registration needed
 * OrdersApi orders = services.api(OrdersApi.class);
 * // same, with another auth profile from config/env/<env>.properties
 * OrdersApi asAdmin = services.asUser("admin").api(OrdersApi.class);
 * }</pre>
 * Hand-written domain services (see the add-api-service skill) get a factory method here, e.g.
 * {@code public OrderService orders() { return new OrderService(api(OrdersApi.class), ctx); }}.
 */
public class ServiceFactory {

  private final ApiClient client;
  private final ScenarioContext ctx;
  private String authProfile = "default";

  // the only public constructor: PicoContainer injects the scenario's client and context
  public ServiceFactory(ApiClient client, ScenarioContext ctx) {
    this.client = client;
    this.ctx = ctx;
  }

  /** Same factory with other credentials; does not change this one. */
  public ServiceFactory asUser(String profile) {
    ServiceFactory other = new ServiceFactory(client, ctx);
    other.authProfile = profile;
    return other;
  }

  /** Runs any approved JSON test case (used by every generated scenario). */
  public OperationService operations() { return new OperationService(client); }

  /** Any generated {@code <Tag>Api} class, bound to this scenario's client and auth profile. */
  public <T extends BaseApi> T api(Class<T> type) {
    try {
      Constructor<T> c = type.getConstructor(ApiClient.class, String.class);
      return c.newInstance(client, authProfile);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(type.getSimpleName()
          + " needs a public (ApiClient, String) constructor; regenerate it with the services command", e);
    }
  }

  public ApiClient client() { return client; }

  public ScenarioContext context() { return ctx; }

  public String authProfile() { return authProfile; }
}
