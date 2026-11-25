---
description: Take a spec all the way (AI test cases → human approval → AI automation → run → AI RCA + heal)
agent: api-qa
---
Spec: `${input:spec:specs/your-api.yaml}`   Environment: `${input:env:qa}`   Tags: `${input:tags:@api}`

1. `java -jar target/api-test-platform.jar status`. Answer anything queued first (`ai-assist` skill).
2. No cases for this spec yet: `ai spec-audit --spec <spec>` and `ai testcases --spec <spec>` (answer + apply when queued).
3. **Human gate**: if any case is PENDING_REVIEW, stop and ask for the review at http://localhost:8080. Do not continue
   until the person says the review is done.
4. `ai automation` → answer + apply → `mvn -q test -Dcucumber.execution.dry-run=true`.
5. `./scripts/run-tests.sh <env> "<tags>"` → `ai rca` → `ai heal` (once) → re-run.
6. Summary: pass rate, API root causes by owner, what was healed, `reports/index.html#rca`.
