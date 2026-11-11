---
name: report-rca-tab
description: Change the HTML test report (org.rg.reporter.ModernCucumberReporter, reports/index.html) or its RCA view and RCA tab. Use when someone wants the report to look different, wants more RCA detail in it, or the RCA does not show up in the report.
---

# The report and its RCA view

`reports/index.html` is written during `mvn test` by `org.rg.reporter.ModernCucumberReporter`
(registered in `ApiTestRunner` as `org.rg.reporter.ModernCucumberReporter:reports`). It is one self-contained file:
`HtmlReportWriter` inlines `src/test/resources/modern-reporter/ui/styles.css` and `app.js` and puts all data in
`<script type="application/json" id="report-data">`.

The RCA runs after the tests, so `com.qa.platform.report.ReportRcaMerger` (CLI `report` / `post-run`, and the
Execute button of the approval UI) adds `target/rca/rca.json` to that JSON as the `rca` field. `app.js` shows it in:
- the **RCA** view (`#rca`, key `3`): KPIs, by category, by owner, one card per root cause
- the **RCA** tab of a failed test's drawer, plus a callout on its Errors tab and an RCA button on Failures cards
- "Copy prompt", which appends the rule-based RCA

Tests and RCA entries are matched by the scenario's `@TC-<id>` tag (`caseIdOf` in `app.js`); `relatedCaseIds`
map the other tests of the same root cause.

## Change the look
1. Edit `src/test/resources/modern-reporter/ui/styles.css` (RCA rules are at the end, under "RCA view") or the
   `// ---------- RCA ----------` section of `app.js`. Plain JS, no build step; keep using the `html` tagged
   template so every value stays escaped.
2. Rebuild the page without running tests: re-run one scenario (`mvn -q test -Dcucumber.filter.tags=@TC-<id>`)
   and `java -jar target/api-test-platform.jar report`, then open `reports/index.html#rca`.
3. Check light and dark theme (`t`), a narrow window with the drawer open, and a run with no failures
   (the RCA view must say "Nothing to analyse").

## RCA missing from the report
- `target/rca/rca.json` absent: run `java -jar target/api-test-platform.jar rca` (or `post-run`).
- "has no report-data block": `reports/index.html` is not from ModernCucumberReporter; check the runner plugin list.
- RCA view filled but a test has no RCA tab: its scenario has no `@TC-` tag (hand-written feature) or it failed
  before the evidence hook ran.

## Data available to app.js
- `DATA.tests[]`: id, title, tags, status/category, attempts[] (steps, errors, attachments incl. `evidence.json`,
  stdout, prompt), history
- `DATA.rca`: runId, failures, groups, newFailures, byCategory, byOwner, entries[] (see
  `src/main/resources/schemas/rca.schema.json`: caseId, relatedCaseIds, category, confidence, summary,
  probableCause, suggestedFix, owner, newFailure, history, curl, bugTicket, otherPossibleCauses, analysedBy)

## Rules
- The category and confidence come from `rca/rules`; the page never re-classifies.
- Status colours always come with a text label.
- Nothing unmasked: the merger masks `rca.json` again, but search the page for `Bearer ` followed by a real token,
  or `password":"`, before sharing a report.
