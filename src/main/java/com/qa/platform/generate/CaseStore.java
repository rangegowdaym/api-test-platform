package com.qa.platform.generate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.qa.platform.common.Json;
import com.qa.platform.model.TestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File store for test cases: testcases/<tag>/<operationId>.json (a JSON array per endpoint).
 * Git is the source of truth; the approval UI database mirrors it and writes status changes back here.
 */
public class CaseStore {

  private static final TypeReference<List<TestCase>> LIST = new TypeReference<>() {};
  private final Path root;

  public CaseStore(Path root) { this.root = root; }

  public Path root() { return root; }

  public Path fileFor(TestCase tc) {
    String tag = tc.tags() == null || tc.tags().isEmpty() ? "default" : tc.tags().get(0);
    return root.resolve(safe(tag)).resolve(safe(tc.source().operationId()) + ".json");
  }

  public List<TestCase> readAll() {
    if (!Files.exists(root)) return List.of();
    List<TestCase> all = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      // folders starting with "_" (e.g. testcases/_proposed/ written by agents) are not part of the store
      for (Path f : files.filter(p -> p.toString().endsWith(".json"))
          .filter(p -> !root.relativize(p).toString().startsWith("_"))
          .sorted().toList()) {
        all.addAll(Json.MAPPER.readValue(f.toFile(), LIST));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return all;
  }

  public Map<String, TestCase> readAllById() {
    Map<String, TestCase> m = new LinkedHashMap<>();
    readAll().forEach(t -> m.put(t.id(), t));
    return m;
  }

  /** Cases generated from one spec file (matched on source.spec, e.g. "orders.yaml"); CUSTOM cases have the same source. */
  public Map<String, TestCase> readBySpec(String specName) {
    Map<String, TestCase> m = new LinkedHashMap<>();
    for (TestCase t : readAll()) {
      if (t.source() != null && specName.equals(t.source().spec())) m.put(t.id(), t);
    }
    return m;
  }

  /** Writes the given cases grouped by file, replacing those files. */
  public void writeAll(List<TestCase> cases) {
    Map<Path, List<TestCase>> byFile = new LinkedHashMap<>();
    for (TestCase c : cases) byFile.computeIfAbsent(fileFor(c), k -> new ArrayList<>()).add(c);
    byFile.forEach(Json::write);
  }

  /** Replace one case in its file (used by the approval UI). */
  public void upsert(TestCase tc) {
    Path f = fileFor(tc);
    List<TestCase> list = new ArrayList<>();
    try {
      if (Files.exists(f)) list.addAll(Json.MAPPER.readValue(f.toFile(), LIST));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    list.removeIf(x -> x.id().equals(tc.id()));
    list.add(tc);
    list.sort((a, b) -> a.id().compareTo(b.id()));
    Json.write(f, list);
  }

  private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._-]+", "_"); }
}
