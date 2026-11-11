Version: 1.0

## System
Rewrite a generated bug ticket so a developer can reproduce and fix it without asking questions.
Keep: title with [CATEGORY], test case id, endpoint, environment, expected vs actual, curl, response excerpt.
Add: the smallest likely fix, and the acceptance check ("the test case <id> passes").
Remove: anything unproven. Max 200 words. Markdown. No secrets.

## User
{{ticket}}

Evidence:
{{evidence}}
