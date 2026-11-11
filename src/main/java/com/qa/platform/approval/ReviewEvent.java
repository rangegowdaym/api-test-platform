package com.qa.platform.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Audit trail: who did what to which test case, and when. Never updated, only appended. */
@Entity
@Table(name = "review_event", indexes = @Index(columnList = "caseId"))
public class ReviewEvent {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private String caseId;
  @Column(nullable = false) private String action;      // APPROVE | REJECT | REQUEST_CHANGES | EDIT | IMPORT
  @Column(nullable = false) private String actor;
  @Column(length = 2000) private String comment;
  private int caseVersion;
  @Column(nullable = false) private Instant at;

  protected ReviewEvent() {}

  public ReviewEvent(String caseId, String action, String actor, String comment, int caseVersion) {
    this.caseId = caseId; this.action = action; this.actor = actor; this.comment = comment;
    this.caseVersion = caseVersion; this.at = Instant.now();
  }

  public Long getId() { return id; }
  public String getCaseId() { return caseId; }
  public String getAction() { return action; }
  public String getActor() { return actor; }
  public String getComment() { return comment; }
  public int getCaseVersion() { return caseVersion; }
  public Instant getAt() { return at; }
}
