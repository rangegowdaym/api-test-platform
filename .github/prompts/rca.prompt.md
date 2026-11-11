---
description: Explain the failures of the last run and write a triage summary
agent: rca-analyst
---
Analyse the last run as your instructions describe. If no LLM ran (entries say "analysedBy": "rules"),
write the narrative yourself with `ai rca-narrative --mode agent` / `ai rca-narrative --apply --by copilot-agent` (step 2): no token needed.
Reply with the headline, the top 3 actions with owners,
and the paths of `target/rca/triage.md` and `reports/index.html#rca`.
