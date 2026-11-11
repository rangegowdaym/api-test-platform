package com.qa.automation.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config/env/<env>.properties, chosen with -Denv=qa (default qa), plus the optional git-ignored
 * config/env/<env>.local.properties on top of it.
 * Precedence: -Dkey=value  >  environment variable KEY_NAME (dots -> underscores, upper case)  >  file.
 * Values may reference environment variables: client.secret=${ORDERS_CLIENT_SECRET}. Never commit secrets.
 */
public final class TestConfig {

  private static final Pattern VAR = Pattern.compile("\\$\\{([A-Z0-9_]+)}");
  private static final TestConfig INSTANCE = new TestConfig(System.getProperty("env", "qa"));

  private final String env;
  private final Properties props = new Properties();

  private TestConfig(String env) {
    this.env = env;
    Path file = Path.of(System.getProperty("platform.home", "."), "config", "env", env + ".properties");
    if (!Files.exists(file)) throw new IllegalStateException("No config for env '" + env + "': " + file.toAbsolutePath());
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // optional, git-ignored: <env>.local.properties overrides keys for this machine (company URLs, proxy, ...)
    Path local = file.resolveSibling(env + ".local.properties");
    if (Files.exists(local)) {
      try (InputStream in = Files.newInputStream(local)) {
        props.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public static TestConfig get() { return INSTANCE; }

  public String env() { return env; }

  public Optional<String> opt(String key) {
    String v = System.getProperty(key);
    if (v == null) v = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'));
    if (v == null) v = props.getProperty(key);
    return Optional.ofNullable(v).map(TestConfig::expand).filter(s -> !s.isBlank());
  }

  public String get(String key) {
    return opt(key).orElseThrow(() -> new IllegalStateException("Missing config '" + key + "' for env " + env));
  }

  public String get(String key, String def) { return opt(key).orElse(def); }

  public int getInt(String key, int def) { return opt(key).map(Integer::parseInt).orElse(def); }

  /** Keys of the env file (for prefixed groups such as http.header.*). */
  public java.util.Set<String> keys() { return props.stringPropertyNames(); }

  private static String expand(String v) {
    Matcher m = VAR.matcher(v);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String e = System.getenv(m.group(1));
      m.appendReplacement(sb, Matcher.quoteReplacement(e == null ? "" : e));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
