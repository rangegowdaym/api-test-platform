package com.qa.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One generated API test case. This is the contract between generator, approval UI, feature writer,
 * the automation runner and the RCA engine. JSON schema: src/main/resources/schemas/testcase.schema.json
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestCase(
    String id,
    String title,
    String category,
    String priority,
    List<String> tags,
    String status,          // PENDING_REVIEW | APPROVED | REJECTED | NEEDS_CHANGES | OBSOLETE
    int version,
    String change,          // NEW | CHANGED | REMOVED | null
    String contentHash,
    Source source,
    CaseRequest request,
    Expected expected,
    Review review,
    List<String> notes) {

  public static final String PENDING = "PENDING_REVIEW";
  public static final String APPROVED = "APPROVED";

  public record Source(String spec, String specHash, String path, String method, String operationId) {}

  public TestCase withStatus(String s) {
    return new TestCase(id, title, category, priority, tags, s, version, change, contentHash, source, request, expected, review, notes);
  }
  public TestCase withVersion(int v) {
    return new TestCase(id, title, category, priority, tags, status, v, change, contentHash, source, request, expected, review, notes);
  }
  public TestCase withChange(String c) {
    return new TestCase(id, title, category, priority, tags, status, version, c, contentHash, source, request, expected, review, notes);
  }
  public TestCase withHash(String h) {
    return new TestCase(id, title, category, priority, tags, status, version, change, h, source, request, expected, review, notes);
  }
  public TestCase withReview(Review r) {
    return new TestCase(id, title, category, priority, tags, status, version, change, contentHash, source, request, expected, r, notes);
  }
  public TestCase withTags(List<String> t) {
    return new TestCase(id, title, category, priority, t, status, version, change, contentHash, source, request, expected, review, notes);
  }
}
