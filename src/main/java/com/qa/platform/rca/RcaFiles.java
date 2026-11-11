package com.qa.platform.rca;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.RcaEntry;
import com.qa.platform.model.TestCase;
import com.qa.platform.report.ReportRcaMerger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read/update access to target/rca for the AI narrative ({@code ai rca-narrative}, LLM or IDE agent).
 * Updates go through {@link LlmRcaEnricher#merge}: category, owner, grouping and history are never changed.
 */
public class RcaFiles {

  public record NarrativeRequest(String caseId, Map<String, String> vars) {}

  private final Path rcaDir;
  private final Path evidenceDir;
  private final CaseStore cases;

  public RcaFiles(Path rcaDir, Path evidenceDir, CaseStore cases) {
    this.rcaDir = rcaDir;
    this.evidenceDir = evidenceDir;
    this.cases = cases;
  }

  public List<NarrativeRequest> narrativeRequests() throws IOException {
    RcaEngine.Report report = read();
    Map<String, TestCase> byId = cases.readAllById();
    List<NarrativeRequest> out = new ArrayList<>();
    for (RcaEntry r : report.entries()) {
      out.add(new NarrativeRequest(r.caseId(),
          LlmRcaEnricher.vars(r, evidence(r.caseId()), RcaEngine.specFragment(byId.get(r.caseId())))));
    }
    return out;
  }

  /** Merges one answer into rca.json and rewrites that root cause's bug ticket. */
  public synchronized String applyNarrative(String caseId, JsonNode answer, String analysedBy) throws IOException {
    RcaEngine.Report report = read();
    List<RcaEntry> updated = new ArrayList<>();
    RcaEntry changed = null;
    for (RcaEntry r : report.entries()) {
      if (r.caseId().equals(caseId)) {
        changed = LlmRcaEnricher.merge(r, answer, analysedBy);
        changed = RcaEngine.withTicket(changed, BugTicketWriter.render(changed, evidence(caseId)));
        updated.add(changed);
      } else {
        updated.add(r);
      }
    }
    if (changed == null) throw new IllegalArgumentException(caseId + " is not a root cause in rca.json");
    Files.createDirectories(rcaDir.resolve("tickets"));
    Files.writeString(rcaDir.resolve("tickets").resolve(caseId + ".md"), changed.bugTicket());
    Json.write(rcaDir.resolve("rca.json"), new RcaEngine.Report(report.runId(), report.generatedAt(), report.failures(),
        report.groups(), report.byCategory(), report.byOwner(), report.newFailures(), updated));
    return caseId + ": " + changed.summary();
  }

  /** Re-merges rca.json into reports/index.html (if the report exists). */
  public String refreshReport() throws IOException {
    return new ReportRcaMerger().merge(Workspace.REPORTS.resolve("index.html"), rcaDir.resolve("rca.json")).message();
  }

  private RcaEngine.Report read() throws IOException {
    Path f = rcaDir.resolve("rca.json");
    if (!Files.exists(f)) throw new IllegalStateException("No " + f + ": run post-run (or rca) after the tests first");
    return Json.MAPPER.readValue(f.toFile(), RcaEngine.Report.class);
  }

  private Evidence evidence(String caseId) throws IOException {
    Path f = evidenceDir.resolve(caseId + ".json");
    return Files.exists(f) ? Json.MAPPER.readValue(f.toFile(), Evidence.class) : null;
  }
}
