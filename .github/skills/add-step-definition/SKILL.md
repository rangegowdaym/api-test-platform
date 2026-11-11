---
name: add-step-definition
description: Add or change a generic Cucumber step definition in the Rest Assured framework without making it API-specific. Use when a dry run reports an undefined step or a new kind of check is needed.
---

# Add a generic step

First check the step is not a **generated** one: every `When I call the <tag> operation "<operationId>"` step is
written by `java -jar target/api-test-platform.jar services --spec specs/<spec>` into
`src/test/java/com/qa/automation/steps/generated/<Tag>Steps.java`. If one is undefined, re-run that command; never
hand-edit or hand-write files in `steps/generated/` (they are replaced on every run).

1. Confirm the gap: `mvn -q test -Dcucumber.execution.dry-run=true` and copy the exact undefined step text.
2. Check `src/test/java/com/qa/automation/steps/ApiSteps.java` (shared Given/Then), `steps/generated/` and the domain `*Steps` classes: often an existing step already covers it. Business flows belong in a domain step backed by a service (see the `add-api-service` skill), not in ApiSteps.
3. Write the step with Cucumber expressions and parameters, using `ScenarioContext` for state:
```java
@And("the response should have header {string}")
public void hasHeader(String name) {
  if (ctx.response.header(name).isEmpty()) throw new AssertionError("Header " + name + " expected but was missing");
}
// prefer adding the check to ResponseAssert and calling assertThat(ctx.response).hasHeader(...) from the step
```
4. Steps never call Rest Assured; requests go through `ServiceFactory`, checks through `ResponseAssert`.
   Error messages feed the RCA rules. Keep these shapes so classification keeps working:
   `Expected status X but was Y`, `Schema validation failed ...`, `JSON path $.a ... expected [..] but was [..]`,
   `Response time N ms exceeded M ms`, `Missing test data: ...`.
5. If generated features should use the step, add it to `FeatureWriter.render()` and to the `gherkin()` preview in
   `src/main/resources/static/app.js`, then regenerate features.
6. Verify: `mvn -q test -Punit`, a dry run, and one real scenario that uses the step.
