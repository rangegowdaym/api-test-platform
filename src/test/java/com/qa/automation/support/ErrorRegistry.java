package com.qa.automation.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hands the failing step + exception from the event plugin to the @After hook (keyed by Cucumber's test case id). */
public final class ErrorRegistry {
  public record Failure(String step, Throwable error) {}

  private static final Map<String, Failure> FAILURES = new ConcurrentHashMap<>();
  private static final Map<String, Integer> RETRIES = new ConcurrentHashMap<>();

  private ErrorRegistry() {}

  public static void put(String testCaseId, String step, Throwable t) { FAILURES.putIfAbsent(testCaseId, new Failure(step, t)); }
  public static Failure take(String testCaseId) { return FAILURES.remove(testCaseId); }

  public static void retried(String scenarioName) { RETRIES.merge(scenarioName, 1, Integer::sum); }
  public static int retries(String scenarioName) { return RETRIES.getOrDefault(scenarioName, 0); }
}
