---
description: Run an AI task of the platform as the agent (no LLM token needed)
agent: agent
---
Use the `ai-assist` skill for task `${input:task:testcases | automation | rca | spec-audit}`
with arguments `${input:args:e.g. --spec specs/orders.yaml}`.

1. Run `java -jar target/api-test-platform.jar ai <task> <args> --mode agent`.
2. Answer every request in `target/ai/<task>/requests.md` into the answer file (or folder, for automation) it names.
3. Run `java -jar target/api-test-platform.jar ai <task> --apply --by copilot-agent`, fix rejected answers, apply again.
Reply with what was applied and where to look (e.g. "12 cases Pending review at http://localhost:8080").
