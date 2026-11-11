package com.qa.platform.ai;

import com.qa.platform.common.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Spec review before generating cases (prompts/spec-audit.md) -> target/platform/spec-audit-<spec>.md. */
final class SpecAuditTask implements AiTask {
  private final String spec;

  SpecAuditTask(String spec) {
    if (spec == null || spec.isBlank()) throw new IllegalArgumentException("spec-audit needs --spec");
    this.spec = spec;
  }

  public String name() { return "spec-audit"; }
  public String format() { return "markdown"; }
  public Map<String, String> args() { return Map.of("spec", spec); }
  public int maxTokens() { return 3000; }
  public String describe() { return "Audit " + spec + " for testability gaps -> target/platform/spec-audit-<spec>.md."; }

  public List<AiRequest> requests() throws Exception {
    SpecText.Spec s = SpecText.load(spec, 60000);
    return List.of(new AiRequest(s.model().specName(), "spec-audit", Map.of("spec", s.text())));
  }

  public String apply(AiRequest request, String answer, String by) throws Exception {
    String md = AiText.unfence(answer, "markdown");
    if (md.length() < 40) throw new IllegalArgumentException("answer too short for an audit");
    Path out = Workspace.OUT.resolve("spec-audit-" + AiText.safeId(request.id()) + ".md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, "<!-- written by " + by + " for " + spec + " (prompts/spec-audit.md) -->\n\n" + AiText.clean(md, 60000) + "\n");
    return "spec audit -> " + out;
  }
}
