package com.qa.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** Written by the automation hooks for every failed (and optionally every) scenario: evidence/<caseId>.json */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Evidence(
    String caseId, String scenario, String feature, List<String> tags, String env, String status,
    long startedAt, long durationMs, int retries,
    HttpRequest request, HttpResponse response,
    String failedStep, String errorType, String errorMessage, String stackTrace,
    Integer expectedStatus, String endpoint, String curl) {

  public record HttpRequest(String method, String url, Map<String, String> headers, String body) {}
  public record HttpResponse(Integer status, Map<String, String> headers, String body, Long timeMs) {}

}
