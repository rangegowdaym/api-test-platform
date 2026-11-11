package com.qa.platform.generate;

import com.qa.platform.model.Endpoint;
import com.qa.platform.model.TestCase;
import java.util.List;

/** One rule = one category of test cases. Add a rule by implementing this and registering it in CaseGenerator. */
public interface CaseRule {
  String category();
  List<TestCase> apply(Endpoint e, CaseFactory f);
}
