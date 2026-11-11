package com.qa.platform.approval;

import com.qa.platform.common.Workspace;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the latest generated report at /reports/index.html#rca, next to the approval UI. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/reports/**")
        .addResourceLocations(Workspace.REPORTS.toAbsolutePath().toUri().toString() + "/");
  }
}
