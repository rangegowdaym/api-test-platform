---
name: swagger-to-testcases
description: Turn a Swagger/OpenAPI file (YAML or JSON, v2 or v3), a URL, a Postman collection or a Markdown/HTML API doc into JSON test cases for human review. Use when someone gives a spec or API doc and wants test cases.
---

# Spec → JSON test cases

## 1. Put the input where the tools expect it
- Files go in `specs/` (keep the original name). URLs can be passed directly.
- Supported: Swagger 2.0, OpenAPI 3.0/3.1 (YAML/JSON), Postman collection v2.x (`*.json` with `info._postman_id`),
  Markdown (`*.md`) or HTML (`*.html`) docs with lines like `POST /orders`.

## 2. Ingest and check
```bash
ls target/api-test-platform.jar || mvn -q -DskipTests package
java -jar target/api-test-platform.jar ingest --spec specs/<file>
cat target/platform/ingest-report.json
```
If `warnings` is not empty, read them out to the person. For Postman/Markdown, look at
`target/platform/normalized-openapi.yaml`: wrong paths or bodies there mean wrong tests later.
For a real audit, use the `spec-analyst` agent.

## 3. Generate
```bash
java -jar target/api-test-platform.jar generate --spec specs/<file>
# narrower first pass for big specs:
java -jar target/api-test-platform.jar generate --spec specs/<file> --include-tags orders --categories POSITIVE,AUTH,MISSING_REQUIRED
java -jar target/api-test-platform.jar status
```
Regenerating is safe: unchanged cases keep their approval; changed ones go back to PENDING_REVIEW; removed ones become OBSOLETE.

## 4. What each category means
| Category | One case per | Expected |
| --- | --- | --- |
| POSITIVE | endpoint | first documented 2xx + schema check |
| MISSING_REQUIRED | required body field / query param | documented 400 or 422 |
| TYPE_MISMATCH | top-level body field (max 5) | 400/422 |
| BOUNDARY | min/max length and value, at and past the limit | 2xx at the limit, 4xx past it |
| ENUM | allowed value, plus one invalid | 2xx / 4xx |
| AUTH | secured endpoint: no credentials, invalid token | 401 (or 403) |
| NOT_FOUND | id-like path param | 404 |

When a case says "Expected status assumed" in `notes`, the spec did not document that error code: the reviewer must check it.

## 5. Hand over to a human
Start the UI: `PLATFORM_USERS="alice:<pw>:REVIEWER" java -jar target/api-test-platform.jar serve` → http://localhost:8080.
**Do not approve cases yourself.** To add business cases, use the `test-designer` agent (it writes to `testcases/_proposed/`
and runs `propose`).
