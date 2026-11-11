package com.qa.automation.client.auth;

import com.qa.automation.config.TestConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory + cache: profile name -> {@link AuthProvider}, built from config
 * (auth.<profile>.type = oauth2 | bearer | apikey | basic | none). "none" and "invalid" are built in.
 */
public final class AuthProviders {
  private static final Map<String, AuthProvider> CACHE = new ConcurrentHashMap<>();

  private AuthProviders() {}

  public static AuthProvider forProfile(String profile) {
    if (profile == null || profile.isBlank() || "none".equals(profile)) return NoAuth.INSTANCE;
    if ("invalid".equals(profile)) return InvalidTokenAuth.INSTANCE;
    return CACHE.computeIfAbsent(profile, AuthProviders::create);
  }

  private static AuthProvider create(String profile) {
    TestConfig c = TestConfig.get();
    String p = "auth." + profile + ".";
    String type = c.get(p + "type", "none");
    return switch (type) {
      case "oauth2" -> new OAuth2ClientCredentialsAuth(profile, c.get(p + "tokenUrl"), c.get(p + "clientId"),
          c.get(p + "clientSecret"), c.get(p + "scope", ""));
      case "bearer" -> new BearerTokenAuth(c.get(p + "token"));
      case "apikey" -> new ApiKeyAuth(c.get(p + "header", "X-API-Key"), c.get(p + "key"));
      case "basic" -> new BasicAuth(c.get(p + "username"), c.get(p + "password"));
      case "none" -> NoAuth.INSTANCE;
      default -> throw new IllegalStateException("Unknown auth type '" + type + "' for profile " + profile);
    };
  }
}
