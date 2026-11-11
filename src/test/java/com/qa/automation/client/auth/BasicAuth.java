package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;

/** Preemptive HTTP Basic (auth.<profile>.type=basic, .username, .password). */
public final class BasicAuth implements AuthProvider {
  private final String username;
  private final String password;
  public BasicAuth(String username, String password) { this.username = username; this.password = password; }
  @Override public void apply(RequestSpecification spec) { spec.auth().preemptive().basic(username, password); }
}
