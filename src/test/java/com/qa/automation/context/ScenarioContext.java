package com.qa.automation.context;

import com.qa.automation.client.ApiResponse;
import com.qa.automation.client.ExchangeRecorder;
import com.qa.platform.model.Evidence;
import com.qa.platform.model.TestCase;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State of ONE scenario (PicoContainer creates one per scenario, so parallel scenarios never share it).
 * It also records the last HTTP exchange for evidence and keeps the cleanup actions to run afterwards.
 */
public class ScenarioContext implements ExchangeRecorder {

  /** A named cleanup action, run in reverse order after the scenario. */
  public record Cleanup(String name, Runnable action) {}

  public TestCase testCase;
  public String authProfile;
  public String endpoint;                   // "POST /orders"
  public ApiResponse response;

  public final Map<String, Object> vars = new HashMap<>();
  public final Map<String, Object> pathOverrides = new LinkedHashMap<>();
  public final Map<String, Object> queryOverrides = new LinkedHashMap<>();
  public final Map<String, String> headerOverrides = new LinkedHashMap<>();
  public final Map<String, Object> bodyOverrides = new LinkedHashMap<>();   // "$.a.b" -> value

  private final Deque<Cleanup> cleanups = new ArrayDeque<>();
  private Evidence.HttpRequest lastRequest;
  private Evidence.HttpResponse lastResponse;

  @Override
  public void record(Evidence.HttpRequest request, Evidence.HttpResponse response) {
    this.lastRequest = request;
    this.lastResponse = response;
  }

  public Evidence.HttpRequest lastRequest() { return lastRequest; }
  public Evidence.HttpResponse lastResponse() { return lastResponse; }

  public void addCleanup(String name, Runnable action) { cleanups.push(new Cleanup(name, action)); }
  public Deque<Cleanup> cleanups() { return cleanups; }
}
