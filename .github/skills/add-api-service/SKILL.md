---
name: add-api-service
description: Add or update the service layer for an API (generated <Tag>Api endpoint classes, a hand-written <Domain>Service with typed models, and domain steps). Use when someone wants typed, reusable API calls, test-data setup through the API, or hand-written business-flow scenarios.
---

# Add an API service (Steps → Services → Api → ApiClient)

## 1. Generate the endpoint classes from the spec
```bash
java -jar target/api-test-platform.jar services --spec specs/<service>.yaml   # <Tag>Api + <Tag>Steps
ls src/test/java/com/qa/automation/api/generated/      # one <Tag>Api.java per OpenAPI tag
```
Each operation becomes `ApiResponse <operationId>(pathParams..., body?, query?)` plus an overload with a
`Consumer<ApiRequest.Builder>` for one-off changes (header, auth). Never edit these files; re-run after spec changes.
Base URI per tag: `baseUri.<tag>=` in `config/env/<env>.properties` (falls back to `baseUri`).

## 2. Use the generated class directly (no new code)
```java
OrdersApi orders = services.api(OrdersApi.class);              // any <Tag>Api, bound to this scenario
ApiResponse r = orders.getOrderById(42);
assertThat(r).hasStatus(200).hasField("$.status", "open");
OrdersApi asAdmin = services.asUser("admin").api(OrdersApi.class);
```
That is enough for most hand-written steps. Add a service (steps 3-5) when several steps share a business flow.

## 3. Add typed models (only the fields you send or check)
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(Long id, String customerId, List<String> items, String status) {
  public static Order forCustomer(String customerId) { return new Order(null, customerId, List.of("SKU-1"), null); }
}
```

## 4. Write the service (business operations, not endpoints)
```java
public final class OrderService {
  private final OrdersApi api;
  private final ScenarioContext ctx;
  OrderService(OrdersApi api, ScenarioContext ctx) { this.api = api; this.ctx = ctx; }

  /** Creates test data: asserts success, returns the model, registers cleanup. */
  public Order create(Order order) {
    ApiResponse r = api.createOrder(order);
    assertThat(r).hasStatus(200);
    Order created = r.as(Order.class);
    ctx.addCleanup("cancel order " + created.id(), () -> api.cancelOrder(created.id()));
    return created;
  }

  /** For negative tests: raw response, no assertion. */
  public ApiResponse tryCreate(Order order) { return api.createOrder(order); }
}
```
Rules: create-methods assert and clean up; try-methods return the raw `ApiResponse`; no Rest Assured imports.
Register it in `ServiceFactory`:
```java
public OrderService orders() { return new OrderService(api(OrdersApi.class), ctx); }
```

## 5. Domain steps + a hand-written feature
```java
public class OrderSteps {
  private final ScenarioContext ctx;
  private final OrderService orders;
  public OrderSteps(ScenarioContext ctx, ServiceFactory services) { this.ctx = ctx; this.orders = services.orders(); }

  @Given("an order exists for customer {string}")
  public void orderExists(String customerId) { ctx.vars.put("orderId", orders.create(Order.forCustomer(customerId)).id()); }
}
```
```gherkin
# src/test/resources/features/manual/order-lifecycle.feature
@orders @manual
Feature: Order lifecycle
  Scenario: A created order can be fetched
    Given an order exists for customer "CUST-001"
    ...
```
Steps need exactly one public constructor (PicoContainer). Replace `orders`/`Order` with your own tag and fields.

## 6. Verify
```bash
mvn -q test -Punit
mvn test -Dcucumber.filter.tags="@manual"
```
