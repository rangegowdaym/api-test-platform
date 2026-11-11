package com.qa.platform.rca.rules;

import com.qa.platform.model.Evidence;
import com.qa.platform.rca.RcaContext;
import com.qa.platform.rca.RcaRule;

public class ContractDriftRule implements RcaRule {
  public String category() { return "CONTRACT_DRIFT"; }
  public String owner() { return "API developer (or spec owner)"; }

  public double score(Evidence e, RcaContext c) {
    String m = RcaRule.msg(e);
    if (m.contains("Schema validation failed") || m.contains("JsonSchemaValidator") || m.contains("does not match the documented schema")) {
      Integer a = RcaRule.actual(e);
      return a != null && a / 100 == 2 ? 0.9 : 0.6;
    }
    return 0;
  }
  public String summary(Evidence e, RcaContext c) {
    return e.endpoint() + " response no longer matches the documented schema";
  }
  public String probableCause(Evidence e, RcaContext c) {
    String m = RcaRule.msg(e);
    String detail = m.length() > 300 ? m.substring(0, 300) + "..." : m;
    return "The API changed a field (missing, renamed, wrong type or new required field) without the spec changing, "
        + "or the spec changed and the service was not deployed. Validator said: " + detail;
  }
  public String suggestedFix(Evidence e, RcaContext c) {
    return "Compare the response body below with the schema in the spec. If the API is right, update the spec and re-import it "
        + "in the approval UI; if the spec is right, raise a bug against the API.";
  }
}
