package com.qa.automation.support;

import com.qa.automation.config.TestConfig;
import com.qa.automation.context.ScenarioContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves placeholders in test case values:
 *   ${testdata.orderId} -> saved variable "orderId" from an earlier step, else testdata.orderId in config/env/<env>.properties
 *   ${var.name}        -> saved variable only
 *   ${uuid}, ${now}    -> generated
 */
public final class Placeholders {
  private static final Pattern P = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

  private Placeholders() {}

  public static Object resolve(Object value, ScenarioContext ctx) {
    if (!(value instanceof String s) || !s.contains("${")) return value;
    Matcher m = P.matcher(s);
    if (m.matches()) return lookup(m.group(1), ctx);   // whole value -> keep the type of the saved variable
    StringBuilder sb = new StringBuilder();
    while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(lookup(m.group(1), ctx))));
    m.appendTail(sb);
    return sb.toString();
  }

  private static Object lookup(String key, ScenarioContext ctx) {
    switch (key) {
      case "uuid": return java.util.UUID.randomUUID().toString();
      case "now": return java.time.Instant.now().toString();
      default: break;
    }
    if (key.startsWith("var.")) {
      Object v = ctx.vars.get(key.substring(4));
      if (v == null) throw new IllegalStateException("Missing test data: variable '" + key.substring(4) + "' was never saved");
      return v;
    }
    if (key.startsWith("testdata.")) {
      String name = key.substring("testdata.".length());
      if (ctx.vars.containsKey(name)) return ctx.vars.get(name);
      return TestConfig.get().opt(key).orElseThrow(() -> new IllegalStateException(
          "Missing test data: set " + key + " in config/env/" + TestConfig.get().env() + ".properties"));
    }
    return TestConfig.get().get(key);
  }
}
