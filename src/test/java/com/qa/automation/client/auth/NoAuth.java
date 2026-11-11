package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;

/** Sends no credentials (public endpoints and "no auth" negative tests). */
public final class NoAuth implements AuthProvider {
  public static final NoAuth INSTANCE = new NoAuth();
  private NoAuth() {}
  @Override public void apply(RequestSpecification spec) { }
}
