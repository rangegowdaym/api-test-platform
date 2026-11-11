---
name: failure-rca
description: Explain why API tests failed. Runs the rule-based RCA engine (optionally with an LLM narrative), reads evidence, groups failures by root cause and writes triage notes and bug tickets. Use when a run has failures or someone asks "why did these tests fail?".
---

# Failure RCA

## 1. Produce the RCA
```bash
java -jar target/api-test-platform.jar post-run          # rules only, deterministic
java -jar target/api-test-platform.jar post-run --llm    # + LLM narrative over HTTP (LLM_PROVIDER + token, see README "LLM narrative")
```

### Narrative from the IDE agent (no token)
`ai rca-narrative --mode agent` → answer `target/ai/rca-narrative/requests.md` (one JSON answer per root cause:
`{"summary","probableCause","suggestedFix","confidence"}`) → `ai rca-narrative --apply --by copilot-agent`.
`ai triage-summary` works the same way for `target/rca/triage.md`. Details: the `ai-assist` skill.
Never edit `target/rca/rca.json` or `reports/index.html` directly (the guard hook blocks it).

## 2. How the engine decides (src/main/java/com/qa/platform/rca/rules)
| Category | Typical signal | Owner |
| --- | --- | --- |
| ENVIRONMENT | ConnectException, timeout, DNS, 502/503/504 | DevOps |
| AUTH_CONFIG | 401/403 on a case that expects access; token request failed | QA config |
| TEST_DATA | "Missing test data", 404/409 on a positive case | QA data |
| CONTRACT_DRIFT | "Schema validation failed" on a 2xx | API dev / spec owner |
| SERVER_ERROR | 500/501 (esp. for invalid input → missing validation) | API dev |
| FUNCTIONAL_BUG | wrong 2xx/4xx, or right status with wrong data | API dev |
| PERFORMANCE | response-time assertion | API dev / perf |
| FLAKY | passed ≥ 2 of the last runs and flips, or passed on retry | QA |
| TEST_DEFECT | step/JSONPath/NPE errors before or around the HTTP call | QA framework |

Highest score wins; others ≥ 0.3 are listed as "other possible causes". Failures with the same
signature (category + endpoint + normalised error) become one group; an ENVIRONMENT outage groups by host.
The LLM never changes the category or owner, only the summary, cause and fix text.

## 3. Check and triage
For each new group: open `target/evidence/<caseId>.json` and confirm the evidence supports the category.
Write a triage note with `prompts/triage-summary.md` (or use the `rca-analyst` agent). Bug text is ready in
`target/rca/tickets/<caseId>.md`.

## 4. Improve the rules (when the same misclassification repeats)
Add or tune a class in `rca/rules/`, register it in `RcaEngine.rules`, add a case to
`src/test/java/com/qa/platform/FeatureAndRcaTest.java`, run `mvn -q test -Punit`.
