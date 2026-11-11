---
description: Test case JSON files are owned by the approval workflow
applyTo: "testcases/**/*.json"
---
These files are changed only through the approval UI or the `generate` / `propose` commands.
Do not edit them. To suggest new cases, write a JSON array to `testcases/_proposed/<name>.json` that follows
`src/main/resources/schemas/testcase.schema.json` with `"category": "CUSTOM"` and `"status": "PENDING_REVIEW"`,
then run `java -jar target/api-test-platform.jar propose --file testcases/_proposed/<name>.json`.
Never set `"status": "APPROVED"`.
