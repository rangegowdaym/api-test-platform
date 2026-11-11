package com.qa.platform.common;

import java.nio.file.Path;

/** Folder layout shared by CLI, UI, runner, RCA and report. Override the root with -Dplatform.home=... */
public final class Workspace {
  /**
   * Always absolute and normalised: a relative root (".") mixed with absolute paths makes Path.relativize throw
   * "'other' is different type of Path" (e.g. Generate features in the UI).
   */
  public static final Path ROOT = Path.of(System.getProperty("platform.home", ".")).toAbsolutePath().normalize();
  public static final Path SPECS = ROOT.resolve("specs");
  public static final Path TESTCASES = ROOT.resolve("testcases");
  public static final Path FEATURES = ROOT.resolve("src/test/resources/features/generated");
  public static final Path OUT = ROOT.resolve("target/platform");
  public static final Path EVIDENCE = ROOT.resolve("target/evidence");
  public static final Path HISTORY = ROOT.resolve("history");
  public static final Path RCA = ROOT.resolve("target/rca");
  public static final Path REPORTS = ROOT.resolve("reports");
  public static final Path PROMPTS = ROOT.resolve("prompts");
  public static final Path CONFIG = ROOT.resolve("config");

  /** Path relative to the project root with forward slashes, for messages and the UI. */
  public static String rel(Path p) {
    Path abs = p.toAbsolutePath().normalize();
    return (abs.startsWith(ROOT) ? ROOT.relativize(abs) : abs).toString().replace('\\', '/');
  }

  private Workspace() {}
}