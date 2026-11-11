package com.qa.platform.generate;

import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges freshly generated cases with the stored cases OF THE SAME SPEC (callers pass CaseStore.readBySpec):
 *   new id                     -> NEW, PENDING_REVIEW
 *   same id, same contentHash  -> kept as stored (approval and reviewer edits survive)
 *   same id, different hash    -> CHANGED, back to PENDING_REVIEW, version + 1
 *   stored id no longer made   -> REMOVED, OBSOLETE (never deleted silently)
 * Cases a reviewer edited (review.action = EDITED) and CUSTOM cases are never overwritten or removed by the generator.
 */
public class CaseDiffer {

  public record Result(List<TestCase> merged, int added, int changed, int removed, int unchanged) {}

  public Result diff(Map<String, TestCase> stored, List<TestCase> fresh) {
    Map<String, TestCase> old = new LinkedHashMap<>(stored);
    List<TestCase> merged = new ArrayList<>();
    int added = 0, changed = 0, unchanged = 0;

    for (TestCase n : fresh) {
      TestCase o = old.remove(n.id());
      if (o == null) {
        merged.add(n.withChange("NEW"));
        added++;
      } else if (o.review() != null && "EDITED".equals(o.review().action())) {
        merged.add(o);                     // human edit wins
        unchanged++;
      } else if (o.contentHash() == null || !o.contentHash().equals(n.contentHash())) {
        merged.add(n.withChange("CHANGED").withStatus(TestCase.PENDING).withVersion(o.version() + 1));
        changed++;
      } else {
        merged.add(o.withChange(null));
        unchanged++;
      }
    }
    int removed = 0;
    for (TestCase o : old.values()) {
      if ("CUSTOM".equals(o.category())) {   // hand-written / agent-proposed cases are not owned by the generator
        merged.add(o);
        unchanged++;
        continue;
      }
      if (!"OBSOLETE".equals(o.status())) removed++;
      merged.add(o.withChange("REMOVED").withStatus("OBSOLETE"));
    }
    return new Result(merged, added, changed, removed, unchanged);
  }
}
