package com.qa.platform.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.platform.common.Workspace;
import com.qa.platform.model.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApprovalController {

  public record ActionRequest(String comment) {}
  public record BulkRequest(List<String> ids, String action, String comment) {}
  public record EditRequest(JsonNode testCase, String comment) {}

  private final ApprovalService service;
  private final AiService ai;

  public ApprovalController(ApprovalService service, AiService ai) {
    this.service = service;
    this.ai = ai;
  }

  @GetMapping("/me")
  public Map<String, String> me(Principal p) { return Map.of("user", p.getName()); }

  @GetMapping("/summary")
  public Map<String, Object> summary() { return service.summary(); }

  @GetMapping("/testcases")
  public List<TestCase> list(@RequestParam(required = false) String status, @RequestParam(required = false) String tag,
                             @RequestParam(required = false) String category, @RequestParam(required = false) String priority,
                             @RequestParam(required = false) String q) {
    return service.list(status, tag, category, priority, q);
  }

  @GetMapping("/testcases/{id}")
  public TestCase get(@PathVariable String id) { return service.get(id); }

  @GetMapping("/testcases/{id}/history")
  public List<ReviewEvent> history(@PathVariable String id) { return service.history(id); }

  @GetMapping("/activity")
  public List<ReviewEvent> activity() { return service.recent(); }

  @PutMapping("/testcases/{id}")
  public TestCase edit(@PathVariable String id, @RequestBody EditRequest r, Principal p) {
    return service.edit(id, r.testCase(), p.getName(), r.comment());
  }

  @PostMapping("/testcases/{id}/approve")
  public TestCase approve(@PathVariable String id, @RequestBody(required = false) ActionRequest r, Principal p) {
    return service.act(id, "APPROVE", p.getName(), r == null ? null : r.comment());
  }

  @PostMapping("/testcases/{id}/reject")
  public TestCase reject(@PathVariable String id, @RequestBody ActionRequest r, Principal p) {
    return service.act(id, "REJECT", p.getName(), r.comment());
  }

  @PostMapping("/testcases/{id}/request-changes")
  public TestCase requestChanges(@PathVariable String id, @RequestBody ActionRequest r, Principal p) {
    return service.act(id, "REQUEST_CHANGES", p.getName(), r.comment());
  }

  @PostMapping("/testcases/bulk")
  public Map<String, Object> bulk(@RequestBody BulkRequest r, Principal p) {
    return service.bulk(r.ids(), r.action(), p.getName(), r.comment());
  }

  /**
   * Upload a spec or API doc (OpenAPI/Swagger YAML/JSON, Postman, Markdown...) or pass ?url=...; it is saved in specs/
   * and the AI designs the test cases (PENDING_REVIEW). Optional business context for the AI.
   */
  @PostMapping(value = "/imports", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
  public Map<String, Object> importSpec(@RequestPart(name = "file", required = false) MultipartFile file,
                                        @RequestParam(required = false) String url,
                                        @RequestParam(required = false) String context,
                                        Principal p) throws Exception {
    Path target;
    if (file != null && !file.isEmpty()) {
      String name = Path.of(file.getOriginalFilename() == null ? "upload.yaml" : file.getOriginalFilename()).getFileName().toString();
      target = Workspace.SPECS.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_"));
      Files.createDirectories(target.getParent());
      file.transferTo(target);
    } else if (url != null && url.startsWith("http")) {
      String name = url.replaceAll("[?#].*$", "").replaceAll(".*/", "");
      target = Workspace.SPECS.resolve((name.isBlank() ? "spec.yaml" : name).replaceAll("[^A-Za-z0-9_.-]", "_"));
      var resp = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
          .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) throw new IllegalArgumentException("Download failed: HTTP " + resp.statusCode());
      Files.createDirectories(target.getParent());
      Files.writeString(target, resp.body());
    } else {
      throw new IllegalArgumentException("Send a file or ?url=");
    }
    return service.importSpec(target, p.getName(), context, ai);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(ApprovalService.NotFound.class)
  public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }
}
