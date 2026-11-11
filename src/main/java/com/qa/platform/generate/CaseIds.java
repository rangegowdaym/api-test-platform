package com.qa.platform.generate;

import com.qa.platform.model.Endpoint;

/**
 * Stable ids: same endpoint + category + detail -> same id on every run, so approvals survive regeneration.
 * Format: TC-<operationId>-<category>-<detail>
 */
public final class CaseIds {
  private CaseIds() {}

  public static String of(Endpoint e, String category, String detail) {
    String d = detail == null || detail.isBlank() ? "1" : detail.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
    return "TC-" + e.operationId() + "-" + category.toLowerCase().replace('_', '-') + "-" + d;
  }
}
