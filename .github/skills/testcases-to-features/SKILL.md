---
name: testcases-to-features
description: Turn APPROVED test cases into runnable automation (feature files, <Tag>Api, <Tag>Steps with assertions) using the AI automation task. Use after a review in the approval UI, when features are missing or outdated, or when the dry run fails.
---

# Approved cases → automation (the AI writes it, testers review it)

```bash
java -jar target/api-test-platform.jar status                       # how many APPROVED cases per tag
java -jar target/api-test-platform.jar ai automation --mode agent   # [--tag orders]; with an LLM token: --mode llm
#   answer target/ai/automation/requests.md: per tag, write into the answer folder
#     src/test/resources/features/generated/<tag>.feature
#     src/test/java/com/qa/automation/api/generated/<Tag>Api.java
#     src/test/java/com/qa/automation/steps/generated/<Tag>Steps.java
java -jar target/api-test-platform.jar ai automation --apply --by copilot-agent
mvn -q test -Dcucumber.execution.dry-run=true                       # compiles, every step binds
```

In the approval UI the same is button **1 Generate automation (AI)** (needs an LLM token on the server).

## What the AI decides
- Scenarios and wording, one per approved case, tagged `@<caseId>`, `@<tag>`, `@api`.
- Assertions: status, schema, the fields and rules from the spec and the case (never weaker than the case).
- The endpoint class and the step definitions, built on the framework (`BaseApi`, `ServiceFactory`,
  `OperationService.prepare`, `ResponseAssert`, `ApiSteps`; their source is in the request).

## What `--apply` checks
Fixed paths per tag, Java package = folder, every `@TC-` tag is an APPROVED case of that tag, no forbidden calls
(Rest Assured outside ApiClient, sleeps, process/file/network access). Then a header marks the file AI-GENERATED.

## Dry run fails
- Duplicate step text: a Steps class redefined an `ApiSteps` step → reuse the existing one.
- Compile error: fix the file in the answer folder, apply again (never edit around the validation).
- A framework gap (missing helper): `automation-engineer` agent.
