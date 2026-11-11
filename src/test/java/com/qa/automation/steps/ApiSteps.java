package com.qa.automation.steps;

import static com.qa.automation.assertions.ResponseAssert.assertThat;

import com.qa.automation.client.ApiRequest;
import com.qa.automation.client.HttpMethod;
import com.qa.automation.config.TestConfig;
import com.qa.automation.context.ScenarioContext;
import com.qa.automation.services.ServiceFactory;
import com.qa.automation.support.Placeholders;
import com.qa.automation.support.TestCaseRepository;
import com.qa.platform.model.Expected;
import com.qa.platform.model.TestCase;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared steps of every GENERATED feature: the Given steps (test case, auth) and all Then steps (status, schema,
 * assertions, timing). The When step of a generated scenario is per operation and lives in the generated
 * {@code steps.generated.<Tag>Steps}, which calls the generated {@code <Tag>Api}.
 * No HTTP code here: requests go through {@link ServiceFactory}; checks go through ResponseAssert.
 * {@code I send the "<METHOD>" request to "<path>"} is kept for hand-written features and older generated ones.
 */
public class ApiSteps {

  private static final AtomicBoolean HEALTH_CHECKED = new AtomicBoolean(false);

  private final ScenarioContext ctx;
  private final ServiceFactory services;

  public ApiSteps(ScenarioContext ctx, ServiceFactory services) {
    this.ctx = ctx;
    this.services = services;
  }

  // ---------- Given ----------

  @Given("the API under test is reachable")
  public void apiReachable() {
    String health = TestConfig.get().get("health.path", "");
    if (health.isBlank() || !HEALTH_CHECKED.compareAndSet(false, true)) return;
    int status = services.client().send(ApiRequest.builder(HttpMethod.GET, health).build()).status();
    if (status >= 500) throw new IllegalStateException("Environment unhealthy: " + health + " returned " + status);
  }

  @Given("test case {string}")
  public void testCase(String id) {
    ctx.testCase = TestCaseRepository.get(id);
    ctx.authProfile = ctx.testCase.request().auth();
    ctx.endpoint = ctx.testCase.source().method() + " " + ctx.testCase.source().path();
  }

  @And("I am authenticated as {string}")
  public void authenticatedAs(String profile) { ctx.authProfile = profile; }

  @And("I send no credentials")
  public void noCredentials() { ctx.authProfile = "none"; }

  @And("I use invalid credentials")
  public void invalidCredentials() { ctx.authProfile = "invalid"; }

  @And("I set header {string} to {string}")
  public void header(String name, String value) { ctx.headerOverrides.put(name, value); }

  @And("I set path parameter {string} to {string}")
  public void pathParam(String name, String value) { ctx.pathOverrides.put(name, Placeholders.resolve(value, ctx)); }

  @And("I set query parameter {string} to {string}")
  public void queryParam(String name, String value) { ctx.queryOverrides.put(name, Placeholders.resolve(value, ctx)); }

  @And("I set body field {string} to {string}")
  public void bodyField(String path, String value) { ctx.bodyOverrides.put(path, Placeholders.resolve(value, ctx)); }

  // ---------- When ----------

  @When("I send the {string} request to {string}")
  public void send(String method, String path) {
    TestCase tc = Objects.requireNonNull(ctx.testCase, "Use 'Given test case \"<id>\"' first");
    if (!tc.source().method().equalsIgnoreCase(method) || !tc.source().path().equals(path)) {
      throw new IllegalStateException("Step says " + method + " " + path + " but test case " + tc.id()
          + " is " + ctx.endpoint + ". Regenerate features.");
    }
    ctx.response = services.operations().execute(tc, ctx);
  }

  // ---------- Then ----------

  @Then("the response status should be {int}")
  public void status(int expected) { assertThat(ctx.response).hasStatus(expected); }

  @And("the response should match the documented schema")
  public void schema() { assertThat(ctx.response).matchesSchema(ctx.testCase.expected().schema()); }

  @And("the response should meet the test case assertions")
  public void assertions() {
    if (ctx.testCase.expected().assertions() == null) return;
    for (Expected.Assertion a : ctx.testCase.expected().assertions()) {
      assertThat(ctx.response).hasField(a.path(), a.op(), Placeholders.resolve(a.value(), ctx));
    }
  }

  @And("the response time should be under {long} ms")
  public void responseTime(long maxMs) { assertThat(ctx.response).respondedWithin(maxMs); }

  @And("the response field {string} should equal {string}")
  public void fieldEquals(String jsonPath, String expected) {
    assertThat(ctx.response).hasField(jsonPath, Placeholders.resolve(expected, ctx));
  }

  @And("the response header {string} should be {string}")
  public void headerEquals(String name, String expected) { assertThat(ctx.response).hasHeader(name, expected); }

  @And("I save {string} as {string}")
  public void save(String jsonPath, String name) {
    Object v = ctx.response.path(jsonPath);
    if (v == null) throw new AssertionError("JSON path " + jsonPath + " not found in response, cannot save '" + name + "'");
    ctx.vars.put(name, v);
  }
}
