---
description: Generated feature files are rebuilt from approved test cases
applyTo: "src/test/resources/features/**/*.feature"
---
Files in `features/generated/` are written by the AI (`ai automation`, header `# AI-GENERATED`) from the APPROVED
cases, one scenario per case tagged `@<caseId>`. Regenerate through `ai automation --tag <tag>`; testers review the diff.
Hand-written scenarios go in `features/manual/`.
