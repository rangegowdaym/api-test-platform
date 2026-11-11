package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;

/** A syntactically valid but wrong bearer token, for "invalid credentials" negative tests. */
public final class InvalidTokenAuth implements AuthProvider {
  public static final InvalidTokenAuth INSTANCE = new InvalidTokenAuth();
  private InvalidTokenAuth() {}
  @Override public void apply(RequestSpecification spec) { spec.header("Authorization", "Bearer invalid.token.for-negative-test"); }
}
