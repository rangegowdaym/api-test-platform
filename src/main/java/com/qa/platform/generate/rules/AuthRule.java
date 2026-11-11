package com.qa.platform.generate.rules;

import com.qa.platform.generate.CaseFactory;
import com.qa.platform.generate.CaseRule;
import com.qa.platform.model.CaseRequest;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.List;

/** Secured endpoints: no credentials and invalid credentials. */
public class AuthRule implements CaseRule {
  @Override public String category() { return "AUTH"; }

  @Override
  public List<TestCase> apply(Endpoint e, CaseFactory f) {
    if (!e.secured()) return List.of();
    CaseRequest valid = f.validRequest(e);
    List<String> notes = new ArrayList<>();
    int unauth = f.clientError(e, notes, "401", "403");
    return List.of(
        f.build(e, category(), "no_auth", "P1",
            e.method() + " " + e.path() + " without credentials returns " + unauth,
            CaseFactory.withAuth(valid, "none"), unauth, false, notes),
        f.build(e, category(), "invalid_token", "P1",
            e.method() + " " + e.path() + " with an invalid token returns " + unauth,
            CaseFactory.withAuth(valid, "invalid"), unauth, false, notes));
  }
}
