package com.qa.platform.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.qa.platform.common.Json;
import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.Review;
import com.qa.platform.model.TestCase;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All review actions go through here: one place for status rules, file writes and the audit trail. */
@Service
public class ApprovalService {

  private final CaseStore store;
  private final ReviewEventRepository events;
  private final JsonSchema caseSchema;

  public ApprovalService(CaseStore store, ReviewEventRepository events) throws Exception {
    this.store = store;
    this.events = events;
    try (InputStream in = getClass().getResourceAsStream("/schemas/testcase.schema.json")) {
      this.caseSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    }
  }

  public synchronized List<TestCase> list(String status, String tag, String category, String priority, String q) {
    String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
    return store.readAll().stream()
        .filter(t -> status == null || status.isBlank() || status.equals(t.status()))
        .filter(t -> tag == null || tag.isBlank() || t.tags().contains(tag))
        .filter(t -> category == null || category.isBlank() || category.equals(t.category()))
        .filter(t -> priority == null || priority.isBlank() || priority.equals(t.priority()))
        .filter(t -> needle == null || needle.isBlank()
            || (t.id() + " " + t.title() + " " + t.source().path()).toLowerCase(Locale.ROOT).contains(needle))
        .toList();
  }

  public synchronized TestCase get(String id) {
    TestCase t = store.readAllById().get(id);
    if (t == null) throw new NotFound(id);
    return t;
  }

  public Map<String, Object> summary() {
    List<TestCase> all = store.readAll();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("total", all.size());
    m.put("byStatus", all.stream().collect(Collectors.groupingBy(TestCase::status, Collectors.counting())));
    m.put("byCategory", all.stream().collect(Collectors.groupingBy(TestCase::category, Collectors.counting())));
    m.put("changed", all.stream().filter(t -> "CHANGED".equals(t.change())).count());
    m.put("tags", all.stream().map(t -> t.tags().get(0)).distinct().sorted().toList());
    return m;
  }

  @Transactional
  public synchronized TestCase act(String id, String action, String actor, String comment) {
    TestCase t = get(id);
    String status = switch (action) {
      case "APPROVE" -> TestCase.APPROVED;
      case "REJECT" -> "REJECTED";
      case "REQUEST_CHANGES" -> "NEEDS_CHANGES";
      case "RESET" -> TestCase.PENDING;
      default -> throw new IllegalArgumentException("Unknown action " + action);
    };
    if ("REJECT".equals(action) && (comment == null || comment.isBlank())) {
      throw new IllegalArgumentException("A reason is required to reject " + id);
    }
    if ("OBSOLETE".equals(t.status()) && "APPROVE".equals(action)) {
      throw new IllegalArgumentException(id + " is obsolete (removed from the spec) and cannot be approved");
    }
    TestCase updated = t.withStatus(status).withChange(null).withReview(new Review(actor, action, comment, Instant.now()));
    store.upsert(updated);
    events.save(new ReviewEvent(id, action, actor, comment, t.version()));
    return updated;
  }

  @Transactional
  public synchronized Map<String, Object> bulk(List<String> ids, String action, String actor, String comment) {
    List<String> done = new ArrayList<>(), failed = new ArrayList<>();
    for (String id : ids) {
      try {
        act(id, action, actor, comment);
        done.add(id);
      } catch (RuntimeException e) {
        failed.add(id + ": " + e.getMessage());
      }
    }
    return Map.of("updated", done.size(), "failed", failed);
  }

  /** A reviewer edit: validated against the schema, version + 1, back to PENDING_REVIEW, protected from regeneration. */
  @Transactional
  public synchronized TestCase edit(String id, JsonNode body, String actor, String comment) {
    TestCase current = get(id);
    Set<ValidationMessage> errors = caseSchema.validate(body);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("Invalid test case: " + errors.stream().map(ValidationMessage::getMessage).toList());
    }
    TestCase edited = Json.MAPPER.convertValue(body, TestCase.class);
    if (!id.equals(edited.id())) throw new IllegalArgumentException("The id cannot be changed");
    edited = edited.withVersion(current.version() + 1).withStatus(TestCase.PENDING).withChange("CHANGED")
        .withReview(new Review(actor, "EDITED", comment, Instant.now()));
    edited = edited.withHash(CaseStore.contentHash(edited));
    store.upsert(edited);
    events.save(new ReviewEvent(id, "EDIT", actor, comment, edited.version()));
    return edited;
  }

  /**
   * The AI designs the test cases for the uploaded spec (task "testcases") and merges them with the stored ones;
   * every new or changed case is PENDING_REVIEW. Without an LLM token on the server the spec is saved and the
   * answer says which command to run in Copilot agent mode.
   */
  @Transactional
  public synchronized Map<String, Object> importSpec(Path spec, String actor, String context) throws Exception {
    var runner = new com.qa.platform.ai.AiRunner();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("spec", spec.getFileName().toString());
    if (!runner.llmAvailable()) {
      throw new IllegalStateException("Saved " + Workspace.rel(spec) + ". No LLM token on the server: in VS Code Copilot agent mode run "
          + "/ai testcases --spec " + Workspace.rel(spec) + "  (or configure LLM_PROVIDER, README section 6a)");
    }
    Map<String, String> args = new LinkedHashMap<>();
    args.put("spec", spec.toString());
    if (context != null && !context.isBlank()) args.put("context", context);
    var r = runner.run(com.qa.platform.ai.AiTasks.create("testcases", args), com.qa.platform.ai.AiRunner.Mode.LLM);
    events.save(new ReviewEvent("*", "IMPORT", actor, Workspace.rel(spec) + " (AI: " + String.join("; ", r.messages()) + ")", 0));
    out.put("messages", r.messages());
    out.put("rejected", r.rejected());
    return out;
  }

  public List<ReviewEvent> history(String id) { return events.findByCaseIdOrderByAtDesc(id); }

  public List<ReviewEvent> recent() { return events.findTop50ByOrderByAtDesc(); }

  public Path featureDir() { return Workspace.FEATURES; }

  public static class NotFound extends RuntimeException {
    public NotFound(String id) { super("Test case not found: " + id); }
  }
}
