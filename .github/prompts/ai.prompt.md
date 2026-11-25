---
description: Answer every AI task queued by the platform (UI buttons, runs, CLI) when no LLM token is configured
agent: api-qa
---
Use the `ai-assist` skill.
${input:request:Optional: a task to queue first, e.g. "testcases --spec specs/petstore.json" or "heal"}

1. If a task was given above, run `java -jar target/api-test-platform.jar ai <that task> --mode agent`.
2. Run `java -jar target/api-test-platform.jar ai pending`. If nothing is queued, say so and print `status`.
3. Answer every pending request (oldest first) into the answer file/folder it names.
4. Run `java -jar target/api-test-platform.jar ai apply`; fix rejected answers and apply again.
5. If you applied rca and failures are TEST_DATA/TEST_DEFECT/unclassified, continue with heal (skill step "Then").
Reply in 3-5 lines: what was applied and what is left for a human. The approval UI refreshes on its own.
