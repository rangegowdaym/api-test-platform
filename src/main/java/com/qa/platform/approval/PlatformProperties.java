package com.qa.platform.approval;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** platform.* in application.yml */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(String home, List<String> users, String reportUrl) {}
