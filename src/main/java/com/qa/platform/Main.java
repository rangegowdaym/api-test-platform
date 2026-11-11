package com.qa.platform;

import com.qa.platform.approval.ApprovalApplication;
import com.qa.platform.cli.PlatformCli;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine;

/**
 * One jar, two modes:
 *   java -jar api-test-platform.jar serve [--server.port=8080]   -> approval UI + REST API
 *   java -jar api-test-platform.jar <command> [options]           -> CLI (generate, features, rca, report, ...)
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (args.length > 0 && "serve".equals(args[0])) {
      SpringApplication.run(ApprovalApplication.class, Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    System.exit(new CommandLine(new PlatformCli()).execute(args));
  }
}
