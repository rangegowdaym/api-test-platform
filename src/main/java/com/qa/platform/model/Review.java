package com.qa.platform.model;

import java.time.Instant;

public record Review(String reviewer, String action, String comment, Instant reviewedAt) {}
