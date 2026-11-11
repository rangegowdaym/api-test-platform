package com.qa.platform.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.qa.platform.common.Json;
import com.qa.platform.model.Review;
import com.qa.platform.model.TestCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports proposed test cases (from an agent, a person or the LLM) into testcases/ as PENDING_REVIEW.
 * Schema-checked, duplicates refused, and never APPROVED whatever the input says: approval is a human action.
 */
public class ProposalImporter {

  public record Result(int added, List<String> skipped) {}

  private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(ProposalImporter.class.getResourceAsStream("/schemas/testcase.schema.json"));

  private final CaseStore store;

  public ProposalImporter(CaseStore store) { this.store = store; }

  /** @param cases a JSON array (or {"cases": [...]}) of test cases; {@code proposer} goes into the review trail */
  public Result importCases(JsonNode cases, String proposer, String note) {
    JsonNode list = cases.isArray() ? cases : cases.path("cases");
    if (!list.isArray()) throw new IllegalArgumentException("expected a JSON array of test cases");
    java.util.Set<String> existing = new java.util.HashSet<>(store.readAllById().keySet());
    List<String> skipped = new ArrayList<>();
    int added = 0;
    for (JsonNode node : list) {
      String id = node.path("id").asText("?");
      var errors = SCHEMA.validate(node);
      if (!errors.isEmpty()) {
        skipped.add(id + ": " + errors.stream().limit(3).map(Object::toString).toList());
        continue;
      }
      TestCase tc = Json.MAPPER.convertValue(node, TestCase.class);
      if (existing.contains(tc.id())) {
        skipped.add(tc.id() + ": id already exists");
        continue;
      }
      tc = tc.withStatus(TestCase.PENDING).withChange("NEW").withVersion(1)
          .withReview(new Review(proposer, "PROPOSED", note, Instant.now()));
      store.upsert(tc.withHash(CaseFactory.contentHash(tc)));
      existing.add(tc.id());
      added++;
    }
    return new Result(added, skipped);
  }
}
