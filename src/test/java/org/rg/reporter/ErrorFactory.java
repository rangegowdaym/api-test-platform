package org.rg.reporter;

import org.rg.reporter.model.ReportModels.ErrorModel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Converts Throwables into report errors and classifies failures (assertion = failed, anything else = broken). */
final class ErrorFactory {

    private static final int MAX_STACK_LINES = 80;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern DIGITS = Pattern.compile("\\d+(\\.\\d+)?");

    private final SourceIndex sources;

    ErrorFactory(SourceIndex sources) {
        this.sources = sources;
    }

    ErrorModel toError(Throwable error) {
        ErrorModel model = new ErrorModel();
        model.message = message(error);
        model.stack = stack(error);
        Optional<StackTraceElement> own = firstProjectFrame(error);
        own.flatMap(sources::locationOf).ifPresent(location -> {
            model.location = location;
            String[] parts = location.split(":");
            model.snippet = sources.codeFrame(parts[0], Integer.parseInt(parts[1]), 2, true);
        });
        return model;
    }

    ErrorModel toError(String message, String location) {
        ErrorModel model = new ErrorModel();
        model.message = message;
        model.location = location;
        return model;
    }

    static String message(Throwable error) {
        String text = error.getMessage();
        String type = error.getClass().getSimpleName();
        String result = text == null || text.isBlank() ? type : type + ": " + text.strip();
        return strip(result);
    }

    static boolean isAssertion(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof AssertionError || name.contains("AssertionFailed") || name.contains("ComparisonFailure")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    static String strip(String text) {
        return text == null ? null : ANSI.matcher(text).replaceAll("");
    }

    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /** First line with volatile numbers normalised; used to group identical failures. */
    static String normalize(String message) {
        String line = firstLine(message);
        if (line.isEmpty()) {
            line = "Unknown error";
        }
        line = DIGITS.matcher(line.replaceFirst("^Error:\\s*", "")).replaceAll("N");
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    static String category(String status, String message) {
        String m = message == null ? "" : message;
        if ("interrupted".equals(status)) return "Interrupted";
        if ("timedOut".equals(status)) return "Timeout";
        if (m.startsWith("Undefined step")) return "Undefined step";
        if (m.startsWith("Ambiguous step") || m.contains("AmbiguousStepDefinitionsException")) return "Ambiguous step";
        if (m.contains("strict mode violation")) return "Strict mode violation";
        boolean locatorMissing = Pattern.compile("element\\(s\\) not found|resolved to 0 elements", Pattern.CASE_INSENSITIVE).matcher(m).find()
                || (Pattern.compile("waiting for (locator|getBy|selector)", Pattern.CASE_INSENSITIVE).matcher(m).find()
                && !m.contains("resolved to <"));
        if (locatorMissing) return "Locator not found";
        if (Pattern.compile("AssertionError|AssertionFailedError|ComparisonFailure|Expected:|expected:|but was", Pattern.CASE_INSENSITIVE).matcher(m).find()) {
            return "Assertion failed";
        }
        if (Pattern.compile("Timeout \\d+ms exceeded|TimeoutError|timed out", Pattern.CASE_INSENSITIVE).matcher(m).find()) return "Timeout";
        if (Pattern.compile("net::ERR_|ECONNREFUSED|ENOTFOUND|ECONNRESET|NS_ERROR_|UnknownHostException|ConnectException").matcher(m).find()) {
            return "Network error";
        }
        if (Pattern.compile("has been closed|Target closed", Pattern.CASE_INSENSITIVE).matcher(m).find()) return "Browser or page closed";
        if (m.contains("NullPointerException")) return "Null pointer";
        return m.isEmpty() ? "Unknown" : "Other errors";
    }

    private Optional<StackTraceElement> firstProjectFrame(Throwable error) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = error; t != null && seen.put(t, true) == null; t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                if (!ReportContext.isSkippedFrame(frame.getClassName())
                        && sources.fileForClass(frame.getClassName()).isPresent()) {
                    return Optional.of(frame);
                }
            }
        }
        return Optional.empty();
    }

    private static String stack(Throwable error) {
        List<String> lines = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = error; t != null && seen.put(t, true) == null; t = t.getCause()) {
            if (t != error) {
                lines.add("Caused by: " + firstLine(message(t)));
            }
            for (StackTraceElement frame : t.getStackTrace()) {
                lines.add("    at " + frame);
            }
        }
        if (lines.size() > MAX_STACK_LINES) {
            int hidden = lines.size() - MAX_STACK_LINES;
            lines = new ArrayList<>(lines.subList(0, MAX_STACK_LINES));
            lines.add("    ... " + hidden + " more");
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
