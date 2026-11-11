---
name: failure-rca
description: Root cause analysis of failed API tests by AI: category, owner, grouping, explanation, fix, bug tickets and triage, written into target/rca/rca.json and the report's RCA view. Use after a test run with failures, or when someone asks why tests failed.
---

# RCA: the AI decides, testers review

```bash
java -jar target/api-test-platform.jar post-run          # collects failures (target/rca/failures.json), history, report
java -jar target/api-test-platform.jar post-run --llm    # same, plus the RCA by the LLM API right away (token needed)
```

## As the IDE agent (no token)
1. `java -jar target/api-test-platform.jar ai rca --mode agent`
2. Read `target/ai/rca/requests.md` (masked evidence per failure, the approved expectation, the history) and the
   evidence files; answer with ONE JSON object (format in the request) into the answer file named there:
   - every failure in exactly one entry; failures with the same cause grouped (`relatedCaseIds`)
   - `category`: FUNCTIONAL_BUG, SERVER_ERROR, CONTRACT_DRIFT, PERFORMANCE, ENVIRONMENT, AUTH_CONFIG, TEST_DATA,
     TEST_DEFECT, FLAKY
   - `owner`, `summary`, `probableCause`, `suggestedFix`, `confidence`, `bugTicket` (markdown, with the curl)
   - `triage`: markdown table + next steps
3. `java -jar target/api-test-platform.jar ai rca --apply --by copilot-agent`
   → `target/rca/rca.json`, `target/rca/tickets/`, `target/rca/triage.md`, `reports/index.html#rca`

`--apply` keeps the facts from the evidence (endpoint, curl, history, new/first failed), recomputes the counts,
rejects unknown cases and categories, and leaves forgotten failures as UNCLASSIFIED.

## Rules
- Every claim cites evidence. "Unclear, check X" beats a guess.
- Never change a test or assertion to make it pass; a wrong expectation is TEST_DEFECT, with the spec as proof.
