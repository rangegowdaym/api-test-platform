package com.qa.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.qa.platform.common.Workspace;
import java.nio.file.Path;
import org.testng.annotations.Test;

/** Regression: Generate features in the UI failed with "'other' is different type of Path". */
public class WorkspaceTest {

  @Test
  public void rootIsAbsoluteSoRelativizeNeverMixesPathTypes() {
    assertTrue(Workspace.ROOT.isAbsolute());
    assertTrue(Workspace.FEATURES.isAbsolute());
  }

  @Test
  public void relWorksForRelativeAndAbsoluteInputs() {
    assertEquals(Workspace.rel(Workspace.FEATURES.resolve("orders.feature")), "src/test/resources/features/generated/orders.feature");
    assertEquals(Workspace.rel(Path.of("specs/orders.yaml")), "specs/orders.yaml");
    assertEquals(Workspace.rel(Path.of("specs/../specs/orders.yaml")), "specs/orders.yaml");
  }
}
