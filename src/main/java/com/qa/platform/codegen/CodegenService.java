package com.qa.platform.codegen;

import com.qa.platform.common.Workspace;
import com.qa.platform.ingest.IngestService;
import com.qa.platform.model.ApiModel;
import java.nio.file.Path;
import java.util.List;

/**
 * "Generate services and step definitions": for one spec, writes
 * <ul>
 *   <li>{@code <Tag>Api} endpoint classes  -> src/test/java/com/qa/automation/api/generated/</li>
 *   <li>{@code <Tag>Steps} step definitions -> src/test/java/com/qa/automation/steps/generated/</li>
 * </ul>
 * Used by the {@code services} CLI command and by the approval UI button. Both outputs are compiled by
 * {@code mvn test}, so nothing else has to be rebuilt after running it.
 */
public class CodegenService {

  public static final Path API_OUT = Workspace.ROOT.resolve("src/test/java/com/qa/automation/api/generated");
  public static final Path STEPS_OUT = Workspace.ROOT.resolve("src/test/java/com/qa/automation/steps/generated");

  public record Result(String spec, int apiClasses, int operations, int stepClasses, int steps, List<Path> files) {}

  public Result generate(String spec) throws Exception {
    return generate(spec, API_OUT, STEPS_OUT);
  }

  public Result generate(String spec, Path apiOut, Path stepsOut) throws Exception {
    ApiModel model = new IngestService().ingest(spec, Workspace.OUT);
    ServiceGenerator.Result api = new ServiceGenerator().write(model, apiOut);
    StepGenerator.Result steps = new StepGenerator().write(model, stepsOut);
    List<Path> files = new java.util.ArrayList<>(api.files());
    files.addAll(steps.files());
    return new Result(model.specName(), api.classes(), api.methods(), steps.classes(), steps.steps(), files);
  }
}
