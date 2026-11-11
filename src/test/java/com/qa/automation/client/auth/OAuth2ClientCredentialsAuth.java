package com.qa.automation.client.auth;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;

/**
 * OAuth2 client-credentials grant. The token is fetched once and reused until 60 s before it expires.
 * Thread-safe: one instance per profile is shared by all parallel scenarios (see {@link AuthProviders}).
 */
public final class OAuth2ClientCredentialsAuth implements AuthProvider {
  private final String profile;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String scope;
  private volatile String token;
  private volatile Instant expires = Instant.EPOCH;

  public OAuth2ClientCredentialsAuth(String profile, String tokenUrl, String clientId, String clientSecret, String scope) {
    this.profile = profile;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope;
  }

  @Override
  public void apply(RequestSpecification spec) { spec.header("Authorization", "Bearer " + token()); }

  private String token() {
    if (token != null && Instant.now().isBefore(expires)) return token;
    synchronized (this) {
      if (token != null && Instant.now().isBefore(expires)) return token;
      // token endpoint goes through the same company proxy / trust store as the API calls
      com.qa.automation.client.ClientConfig cc = com.qa.automation.client.ClientConfig.fromTestConfig();
      RequestSpecification req = given().config(cc.restAssured());
      cc.applyProxy(req, tokenUrl);
      Response r = req
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", clientId)
          .formParam("client_secret", clientSecret)
          .formParam("scope", scope == null ? "" : scope)
          .post(tokenUrl);
      if (r.statusCode() != 200) {
        // message shape read by the RCA AuthConfigRule
        throw new IllegalStateException("Token request failed for profile '" + profile + "': HTTP " + r.statusCode());
      }
      long ttl = r.jsonPath().getLong("expires_in");
      token = r.jsonPath().getString("access_token");
      expires = Instant.now().plusSeconds(Math.max(30, ttl - 60));
      return token;
    }
  }
}
