package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;
import java.util.Objects;

/** Static bearer token (auth.<profile>.type=bearer, auth.<profile>.token=${VAR}). */
public final class BearerTokenAuth implements AuthProvider {
  private final String token;
  public BearerTokenAuth(String token) { this.token = Objects.requireNonNull(token, "token"); }
  @Override public void apply(RequestSpecification spec) { spec.header("Authorization", "Bearer " + token); }
}
