---
name: testcases-to-features
description: Generate Cucumber feature files from human-APPROVED JSON test cases and verify they bind to step definitions. Use after review in the approval UI, or when someone asks to "convert approved test cases to feature files".
---

# Approved cases → feature files

```bash
java -jar target/api-test-platform.jar status          # how many are APPROVED?
java -jar target/api-test-platform.jar features        # writes src/test/resources/features/generated/<tag>.feature
java -jar target/api-test-platform.jar services --spec specs/<spec>   # <Tag>Api + <Tag>Steps for the When-steps
mvn -q test -Dcucumber.execution.dry-run=true          # every step must bind
```

## What a generated scenario looks like
```gherkin
@TC-createOrder-missing-required-body_customerId @orders @P2 @regression @missing-required
Scenario: POST /orders without required field 'customerId' returns 400
  Given test case "TC-createOrder-missing-required-body_customerId"
  And I am authenticated as "default"
  # POST /orders
  When I call the orders operation "createOrder"
  Then the response status should be 400
  And the response should match the documented schema
  And the response time should be under 3000 ms
```
The scenario is thin on purpose: request data and expectations are loaded from the approved JSON by id,
so an edit in the UI only needs a regeneration if the case's status changes. The When-step is defined by the
generated `steps/generated/OrdersSteps#createOrder`, which calls `OrdersApi#createOrder` (both from `services`).
In the approval UI the same two commands are the buttons **1 Generate features** and **2 Generate services & steps**;
button 1 lists operations whose step definition is still missing.

## Rules
- Only APPROVED cases are written; PENDING/REJECTED/OBSOLETE are skipped.
- Generated files start with `# GENERATED` and are replaced on each run. Never edit them (a hook blocks it).
- Hand-written scenarios go in `src/test/resources/features/manual/` and may use the same steps plus
  `I set body field "$.x" to "y"`, `I save "$.id" as "orderId"`, `the response field "$.x" should equal "y"`.
- An undefined `When I call the <tag> operation "<id>"` step → run `services --spec` for that spec (its spec file
  must be in `specs/`). Any other undefined step → use the `add-step-definition` skill.
