package org.rg.reporter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps classes, stack frames and feature URIs to project source files and remembers
 * which ones were referenced so they can be embedded in the report (Source tab).
 */
final class SourceIndex {

    private static final long MAX_SOURCE_BYTES = 200 * 1024;
    private static final List<String> JAVA_ROOTS = List.of("src/test/java", "src/main/java");
    private static final List<String> RESOURCE_ROOTS = List.of("src/test/resources", "src/main/resources");

    private final Path baseDir;
    private final Map<String, Optional<String>> classCache = new ConcurrentHashMap<>();
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();

    SourceIndex(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    Path baseDir() {
        return baseDir;
    }

    /** Project-relative path (forward slashes) of the source file declaring {@code className}. */
    Optional<String> fileForClass(String className) {
        return classCache.computeIfAbsent(className, name -> {
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            String relative = outer.replace('.', '/') + ".java";
            for (String root : JAVA_ROOTS) {
                Path candidate = baseDir.resolve(root).resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(root + "/" + relative);
                }
            }
            return Optional.empty();
        });
    }

    /** "file:line:column" for a frame that belongs to the project, tracking the file. */
    Optional<String> locationOf(StackTraceElement frame) {
        if (frame.getLineNumber() <= 0) {
            return Optional.empty();
        }
        return fileForClass(frame.getClassName()).map(file -> {
            tracked.add(file);
            return file + ":" + frame.getLineNumber() + ":" + column(file, frame.getLineNumber());
        });
    }

    /** Resolves a Cucumber feature URI (file: or classpath:) to a project-relative path. */
    String relativeFeature(URI uri) {
        String result = uri.toString();
        if ("classpath".equals(uri.getScheme())) {
            String resource = uri.getSchemeSpecificPart().replaceFirst("^/+", "");
            for (String root : RESOURCE_ROOTS) {
                if (Files.isRegularFile(baseDir.resolve(root).resolve(resource))) {
                    result = root + "/" + resource;
                    break;
                }
            }
            if (result.equals(uri.toString())) {
                result = resource;
            }
        } else if ("file".equals(uri.getScheme())) {
            result = relativize(Paths.get(uri));
        }
        tracked.add(result);
        return result;
    }

    String relativize(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return abs.startsWith(baseDir) ? baseDir.relativize(abs).toString().replace('\\', '/') : abs.toString().replace('\\', '/');
    }

    /** Line of a method declaration, used to locate hooks. Returns 0 when unknown. */
    int methodLine(String file, String methodName) {
        List<String> lines = lines(file);
        Pattern declaration = Pattern.compile("\\b(void|[\\w<>\\[\\],\\s]+)\\s+" + Pattern.quote(methodName) + "\\s*\\(");
        for (int i = 0; i < lines.size(); i++) {
            if (declaration.matcher(lines.get(i)).find()) {
                tracked.add(file);
                return i + 1;
            }
        }
        return 0;
    }

    int column(String file, int line) {
        List<String> lines = lines(file);
        if (line < 1 || line > lines.size()) {
            return 1;
        }
        String text = lines.get(line - 1);
        int indent = 0;
        while (indent < text.length() && Character.isWhitespace(text.charAt(indent))) {
            indent++;
        }
        return indent + 1;
    }

    List<String> lines(String file) {
        try {
            return Files.readAllLines(baseDir.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Playwright-style code frame: "> 12 | code" plus a caret line under the hit. */
    String codeFrame(String file, int line, int radius, boolean caret) {
        List<String> all = lines(file);
        if (line < 1 || line > all.size()) {
            return null;
        }
        int start = Math.max(1, line - radius);
        int end = Math.min(all.size(), line + radius);
        int width = String.valueOf(end).length();
        StringBuilder out = new StringBuilder();
        for (int n = start; n <= end; n++) {
            String number = String.format("%" + width + "d", n);
            out.append(n == line ? "> " : "  ").append(number).append(" | ").append(all.get(n - 1)).append('\n');
            if (caret && n == line) {
                out.append("  ").append(" ".repeat(width)).append(" | ")
                        .append(" ".repeat(column(file, line) - 1)).append("^\n");
            }
        }
        return out.toString().stripTrailing();
    }

    void track(String file) {
        if (file != null) {
            tracked.add(file);
        }
    }

    Map<String, String> readSources() {
        Map<String, String> sources = new TreeMap<>();
        for (String file : tracked) {
            Path path = baseDir.resolve(file);
            try {
                if (Files.isRegularFile(path) && Files.size(path) <= MAX_SOURCE_BYTES) {
                    sources.put(file, Files.readString(path, StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException ignored) {
                // Removed or unreadable since the run started.
            }
        }
        return sources;
    }
}
