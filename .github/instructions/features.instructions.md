---
description: Generated feature files are rebuilt from approved test cases
applyTo: "src/test/resources/features/**/*.feature"
---
Files in `features/generated/` start with `# GENERATED` and are replaced by
`java -jar target/api-test-platform.jar features`. Never edit them; change the test case in the approval UI instead.
Hand-written scenarios go in `features/manual/` and use only the steps defined in `ApiSteps.java`.
