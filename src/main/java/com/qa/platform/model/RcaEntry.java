package com.qa.platform.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** One analysed failure group. JSON schema: src/main/resources/schemas/rca.schema.json */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RcaEntry(
    String caseId, List<String> relatedCaseIds, String scenario, String endpoint,
    String category, double confidence, String signature,
    String summary, String probableCause, String suggestedFix, String owner,
    boolean newFailure, String firstFailedRun, List<String> history,
    String evidenceRef, String curl, String bugTicket,
    List<String> otherPossibleCauses, String analysedBy) {}
