package com.qa.automation.client.auth;

import io.restassured.specification.RequestSpecification;

/** Strategy: adds credentials to a request. One implementation per auth scheme. */
@FunctionalInterface
public interface AuthProvider {
  void apply(RequestSpecification spec);
}
