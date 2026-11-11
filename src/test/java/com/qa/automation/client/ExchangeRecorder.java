package com.qa.automation.client;

import com.qa.platform.model.Evidence;

/** Receives every request/response pair (already masked). ScenarioContext implements it to build evidence. */
@FunctionalInterface
public interface ExchangeRecorder {
  void record(Evidence.HttpRequest request, Evidence.HttpResponse response);

  ExchangeRecorder NONE = (req, res) -> { };
}
