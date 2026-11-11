package com.qa.platform.rca;

import com.qa.platform.common.Masking;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;

/** Copy-paste-ready ticket text (Markdown works in Jira, GitHub, Azure DevOps). */
public final class BugTicketWriter {
  private BugTicketWriter() {}

  public static String render(RcaEntry r, Evidence e) {
    StringBuilder sb = new StringBuilder();
    sb.append("**[").append(r.category()).append("] ").append(r.summary()).append("**\n\n");
    sb.append("- Test case: `").append(r.caseId()).append("`");
    if (r.relatedCaseIds() != null && !r.relatedCaseIds().isEmpty()) sb.append(" (+").append(r.relatedCaseIds().size()).append(" related)");
    sb.append("\n- Endpoint: `").append(r.endpoint()).append("`\n");
    sb.append("- Environment: ").append(e == null ? "" : e.env()).append("\n");
    sb.append("- First failed in run: ").append(r.firstFailedRun()).append("\n");
    sb.append("- Suggested owner: ").append(r.owner()).append("\n\n");
    sb.append("**Expected:** HTTP ").append(e == null ? "?" : e.expectedStatus()).append("  \n");
    sb.append("**Actual:** ").append(e == null || e.response() == null ? "no response" : "HTTP " + e.response().status()).append("\n\n");
    sb.append("**Probable cause:** ").append(r.probableCause()).append("\n\n");
    sb.append("**Suggested fix:** ").append(r.suggestedFix()).append("\n\n");
    if (r.curl() != null) sb.append("**Reproduce:**\n```bash\n").append(r.curl()).append("\n```\n\n");
    if (e != null && e.response() != null && e.response().body() != null) {
      String body = Masking.text(e.response().body());
      sb.append("**Response body:**\n```json\n").append(body.length() > 2000 ? body.substring(0, 2000) + "\n..." : body).append("\n```\n");
    }
    return sb.toString();
  }
}
