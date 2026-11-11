package com.qa.platform.rca;

import com.qa.platform.model.Evidence;

/**
 * One failure category. score() returns 0 (does not apply) .. 1 (certain). The highest score wins;
 * other rules scoring >= 0.3 are listed as "other possible causes".
 */
public interface RcaRule {
  String category();
  String owner();
  double score(Evidence e, RcaContext c);
  /** One-line root cause for a human, built from the evidence. */
  String summary(Evidence e, RcaContext c);
  String probableCause(Evidence e, RcaContext c);
  String suggestedFix(Evidence e, RcaContext c);

  static String msg(Evidence e) { return e.errorMessage() == null ? "" : e.errorMessage(); }
  static String type(Evidence e) { return e.errorType() == null ? "" : e.errorType(); }
  static Integer actual(Evidence e) { return e.response() == null ? null : e.response().status(); }
}
