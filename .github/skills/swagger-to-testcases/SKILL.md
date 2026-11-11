---
name: swagger-to-testcases
description: Turn a Swagger/OpenAPI file, Postman collection or any API doc into JSON test cases for human review, designed by AI. Use when a spec arrives or changes, or someone asks for test cases for an API.
---

# Spec → test cases (the AI designs them, humans approve them)

```bash
cp /path/to/your-api.yaml specs/
java -jar target/api-test-platform.jar ai testcases --spec specs/your-api.yaml --mode agent [--context "business rules"]
#   answer target/ai/testcases/requests.md: one JSON array of cases per part (big specs are split by paths)
java -jar target/api-test-platform.jar ai testcases --apply --by copilot-agent
java -jar target/api-test-platform.jar status
```
With an LLM token: `java -jar target/api-test-platform.jar generate --spec specs/your-api.yaml` (same task, `--mode auto`),
or **Import spec** in the approval UI.

## What a good answer contains
- Every operation covered: POSITIVE, MISSING_REQUIRED, TYPE_MISMATCH, BOUNDARY, ENUM, AUTH (secured ops), NOT_FOUND,
  plus CUSTOM business cases (lifecycle, 409 duplicates, cross-user access, rules from descriptions, pagination).
- Ids `TC-<tag>-<operationId>-<category>-<detail>`, reused for the same intent on re-runs (approvals survive).
- Realistic request data; `${testdata.<name>}` for ids that must exist, `${uuid}` for unique values.
- `expected.status` from the spec; `notes` with "Assumption: ..." where the spec is silent.

## What `--apply` does
Validates each case against `src/main/resources/schemas/testcase.schema.json`, forces `PENDING_REVIEW`, records the
spec and its hash, and merges with the stored cases: unchanged spec → stored cases and approvals stay; changed
content → back to review; cases no longer produced → OBSOLETE (only when every part was answered); human edits win.
