---
description: Show what is waiting for human review and what to look at first
agent: api-qa
---
Run `java -jar target/api-test-platform.jar status`. Then list the PENDING_REVIEW cases whose `notes` say
"Expected status assumed" or whose `"change"` is `"CHANGED"`, grouped by tag, so the reviewer knows where to start.
Read-only: change nothing.
