package com.qa.automation.support;

import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import com.qa.platform.model.TestCase;
import java.util.Map;

/** Loads the approved JSON test cases once per JVM; scenarios look them up by id. */
public final class TestCaseRepository {
  private static volatile Map<String, TestCase> cases;

  private TestCaseRepository() {}

  public static TestCase get(String id) {
    if (cases == null) {
      synchronized (TestCaseRepository.class) {
        if (cases == null) cases = new CaseStore(Workspace.TESTCASES).readAllById();
      }
    }
    TestCase tc = cases.get(id);
    if (tc == null) throw new IllegalStateException("Test case not found: " + id + " (regenerate features?)");
    if (!TestCase.APPROVED.equals(tc.status())) {
      throw new IllegalStateException("Test case " + id + " is " + tc.status() + ", not APPROVED. Regenerate features.");
    }
    return tc;
  }
}
