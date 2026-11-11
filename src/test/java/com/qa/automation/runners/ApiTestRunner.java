package com.qa.automation.runners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.DataProvider;

/**
 * Runs every feature under src/test/resources/features (generated/ + any hand-written ones).
 * Filter:   mvn test -Dcucumber.filter.tags="@smoke and not @flaky"
 * Threads:  mvn test -Dthreads=8
 * Report:   reports/index.html (org.rg.reporter.ModernCucumberReporter), then post-run adds the RCA.
 */
@CucumberOptions(
    features = "src/test/resources/features",
    glue = {"com.qa.automation.steps", "com.qa.automation.hooks"},
    plugin = {
        "pretty",
        "summary",
        "json:target/cucumber.json",
        "html:target/cucumber.html",
        "com.qa.automation.plugin.RunSummaryPlugin",
        // reports/index.html: Dashboard, Failures, RCA, Tests, ... (RCA merged in by the post-run command)
        "org.rg.reporter.ModernCucumberReporter:reports"
    },
    monochrome = true)
public class ApiTestRunner extends AbstractTestNGCucumberTests {

  @Override
  @DataProvider(parallel = true)
  public Object[][] scenarios() {
    return super.scenarios();
  }
}
