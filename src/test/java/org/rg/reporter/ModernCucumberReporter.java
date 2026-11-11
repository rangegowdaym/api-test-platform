package org.rg.reporter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;
import org.rg.reporter.model.ReportModels.AnnotationModel;
import org.rg.reporter.model.ReportModels.AttachmentModel;
import org.rg.reporter.model.ReportModels.AttemptModel;
import org.rg.reporter.model.ReportModels.EnvironmentEntry;
import org.rg.reporter.model.ReportModels.ErrorModel;
import org.rg.reporter.model.ReportModels.GitInfo;
import org.rg.reporter.model.ReportModels.HistoryFile;
import org.rg.reporter.model.ReportModels.ReportData;
import org.rg.reporter.model.ReportModels.RunSummary;
import org.rg.reporter.model.ReportModels.Stats;
import org.rg.reporter.model.ReportModels.StepModel;
import org.rg.reporter.model.ReportModels.SuiteNode;
import org.rg.reporter.model.ReportModels.TestHistoryEntry;
import org.rg.reporter.model.ReportModels.TestResultModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cucumber plugin that writes a modern, standalone (single index.html) report using the same
 * UI as the Playwright TypeScript reporter in /reporters.
 *
 * <pre>
 * plugin = "org.rg.reporter.ModernCucumberReporter:target/modern-report"
 * </pre>
 * The title can be set with -Dreport.title=..., the REPORT_TITLE env variable or ReportContext.title(...).
 */
public class ModernCucumberReporter implements ConcurrentEventListener {

    private static final String DEFAULT_OUTPUT = "target/modern-report";
    private static final String HISTORY_FILE = "history.json";
    private static final int MAX_RUNS = 30;
    private static final int MAX_TEST_HISTORY = 15;
    private static final int MAX_INLINE_IMAGE_BYTES = 1024 * 1024;
    private static final int MAX_INLINE_TEXT_BYTES = 256 * 1024;
    private static final int MAX_PROMPT_STEPS = 150;
    private static final int MAX_PROMPT_OUTPUT = 4000;
    private static final Pattern INLINE_IMAGE = Pattern.compile("^image/(png|jpe?g|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_LOCATION = Pattern.compile("^([\\w.$]+)\\.([\\w$<>]+)\\(.*$");
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"), Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"), Map.entry("image/svg+xml", "svg"), Map.entry("video/webm", "webm"),
            Map.entry("video/mp4", "mp4"), Map.entry("application/zip", "zip"), Map.entry("application/json", "json"),
            Map.entry("text/plain", "txt"), Map.entry("text/html", "html"), Map.entry("text/csv", "csv"),
            Map.entry("application/xml", "xml"), Map.entry("text/xml", "xml"));

    private final String outputFolder;
    private final SourceIndex sources;
    private final ErrorFactory errors;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Map<URI, FeatureInfo> features = new ConcurrentHashMap<>();
    private final Map<UUID, Execution> running = new ConcurrentHashMap<>();
    private final Map<String, TestAggregate> tests = new ConcurrentHashMap<>();
    private final Map<Thread, Integer> workers = new ConcurrentHashMap<>();
    private final AtomicInteger workerCounter = new AtomicInteger();

    private Path outputDir;
    private volatile boolean disabled;
    private Path dataDir;
    private HistoryFile history = new HistoryFile();
    private Instant runStart = Instant.now();

    public ModernCucumberReporter() {
        this(DEFAULT_OUTPUT);
    }

    public ModernCucumberReporter(String outputFolder) {
        this.outputFolder = outputFolder == null || outputFolder.isBlank() ? DEFAULT_OUTPUT : outputFolder;
        this.sources = new SourceIndex(Paths.get(System.getProperty("user.dir")));
        this.errors = new ErrorFactory(sources);
        ReportContext.install(sources, errors);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, this::onRunStarted);
        publisher.registerHandlerFor(TestSourceRead.class, this::onSourceRead);
        publisher.registerHandlerFor(TestCaseStarted.class, this::onTestCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onTestStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onTestStepFinished);
        publisher.registerHandlerFor(EmbedEvent.class, this::onEmbed);
        publisher.registerHandlerFor(WriteEvent.class, this::onWrite);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onTestCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, this::onRunFinished);
    }

    // ------------------------------------------------------------------ event handlers

    private void onRunStarted(TestRunStarted event) {
        runStart = event.getInstant();
        outputDir = sources.baseDir().resolve(outputFolder).toAbsolutePath().normalize();
        try {
            if (sources.baseDir().startsWith(outputDir)) {
                throw new IllegalStateException("it contains the project");
            }
            // Read history before the folder is wiped so trends survive between runs.
            history = readHistory(outputDir.resolve(HISTORY_FILE));
            deleteRecursively(outputDir);
            dataDir = outputDir.resolve("data");
            Files.createDirectories(dataDir);
        } catch (IOException | RuntimeException e) {
            // A reporting problem must never break the test run itself.
            disable("cannot use output folder \"" + outputDir + "\" (" + e + ")."
                    + " Use a project-relative path such as \"target/modern-report\" or \"reports\""
                    + " - a leading '/' means the filesystem root.");
        }
    }

    private void disable(String reason) {
        disabled = true;
        System.err.println("[ModernCucumberReporter] Report disabled: " + reason);
    }

    private void onSourceRead(TestSourceRead event) {
        features.put(event.getUri(), FeatureInfo.parse(sources.relativeFeature(event.getUri()), event.getSource()));
    }

    private void onTestCaseStarted(TestCaseStarted event) {
        Execution execution = new Execution(event.getInstant().toEpochMilli(),
                workers.computeIfAbsent(Thread.currentThread(), t -> workerCounter.getAndIncrement()));
        running.put(event.getTestCase().getId(), execution);
        ReportContext.beginTest(execution.stdout);
    }

    private void onTestStepStarted(TestStepStarted event) {
        Execution execution = running.get(event.getTestCase().getId());
        if (execution == null) {
            return;
        }
        TestStep step = event.getTestStep();
        StepModel model = new StepModel();
        model.startTime = event.getInstant().toEpochMilli();
        if (step instanceof PickleStepTestStep pickleStep) {
            model.title = pickleStep.getStep().getKeyword().strip() + " " + pickleStep.getStep().getText();
            model.category = ReportContext.STEP;
            FeatureInfo feature = features.get(pickleStep.getUri());
            int line = pickleStep.getStep().getLine();
            model.location = feature == null ? null : feature.file + ":" + line + ":" + feature.column(line);
        } else if (step instanceof HookTestStep hook) {
            model.category = "hook";
            model.title = hookTitle(hook);
            model.location = hookLocation(hook.getCodeLocation());
        } else {
            model.category = ReportContext.STEP;
            model.title = step.getCodeLocation();
        }
        execution.steps.add(model);
        execution.current = model;
        ReportContext.beginStep(model);
    }

    private void onTestStepFinished(TestStepFinished event) {
        Execution execution = running.get(event.getTestCase().getId());
        ReportContext.endStep();
        if (execution == null || execution.current == null) {
            return;
        }
        StepModel model = execution.current;
        execution.current = null;
        Result result = event.getResult();
        model.duration = result.getDuration().toMillis();
        Status status = result.getStatus();
        switch (status) {
            case FAILED, AMBIGUOUS, UNDEFINED -> {
                model.status = "failed";
                boolean childFailed = model.steps.stream().anyMatch(s -> "failed".equals(s.status));
                if (result.getError() != null && !childFailed) {
                    model.error = errors.toError(result.getError());
                } else if (result.getError() == null) {
                    model.error = errors.toError(syntheticMessage(status, model.title), model.location);
                }
                if (execution.failedStep == null) {
                    execution.failedStep = model;
                }
            }
            case SKIPPED, PENDING, UNUSED -> {
                if ("hook".equals(model.category)) {
                    execution.steps.remove(model);
                } else {
                    model.title = model.title + (status == Status.PENDING ? " (pending)" : " (skipped)");
                }
            }
            default -> {
                // passed
            }
        }
    }

    private void onEmbed(EmbedEvent event) {
        Execution execution = running.get(event.getTestCase().getId());
        if (execution == null || disabled) {
            return;
        }
        String name = event.getName() == null || event.getName().isBlank() ? "attachment" : event.getName();
        AttachmentModel attachment = toAttachment(name, event.getMediaType(), event.getData());
        execution.attempt.attachments.add(attachment);
        StepModel parent = ReportContext.currentStep();
        if (parent != null) {
            StepModel marker = new StepModel();
            marker.title = "Attach \"" + name + "\"";
            marker.category = "test.attach";
            marker.startTime = event.getInstant().toEpochMilli();
            parent.steps.add(marker);
        }
    }

    private void onWrite(WriteEvent event) {
        Execution execution = running.get(event.getTestCase().getId());
        if (execution != null) {
            execution.stdout.append(event.getText()).append('\n');
        }
    }

    private void onTestCaseFinished(TestCaseFinished event) {
        Execution execution = running.remove(event.getTestCase().getId());
        String project = firstNonBlank(ReportContext.currentProject(), System.getProperty("env"), "default");
        String browser = firstNonBlank(ReportContext.currentBrowser(), System.getProperty("browser"), "api");
        ReportContext.endTest();
        if (execution == null) {
            return;
        }
        TestCase testCase = event.getTestCase();
        Result result = event.getResult();
        AttemptModel attempt = execution.attempt;
        attempt.duration = result.getDuration().toMillis();
        attempt.steps = execution.steps;
        attempt.stdout = ErrorFactory.strip(execution.stdout.toString());
        attempt.status = switch (result.getStatus()) {
            case PASSED -> "passed";
            case FAILED, AMBIGUOUS, UNDEFINED -> "failed";
            default -> "skipped";
        };
        boolean assertion = false;
        if ("failed".equals(attempt.status)) {
            if (result.getError() != null) {
                attempt.errors.add(errors.toError(result.getError()));
                assertion = ErrorFactory.isAssertion(result.getError());
            } else if (execution.failedStep != null && execution.failedStep.error != null) {
                attempt.errors.add(execution.failedStep.error);
            } else {
                attempt.errors.add(errors.toError(syntheticMessage(result.getStatus(), testCase.getName()), null));
            }
        }

        FeatureInfo feature = features.get(testCase.getUri());
        String key = project + "|" + testCase.getUri() + ":" + testCase.getLocation().getLine();
        TestAggregate aggregate = tests.computeIfAbsent(key, k -> new TestAggregate(shortHash(k), testCase, feature, project, browser));
        synchronized (aggregate) {
            attempt.retry = aggregate.attempts.size();
            attempt.prompt = buildPrompt(aggregate, attempt);
            aggregate.attempts.add(attempt);
            aggregate.assertionFailures.add(assertion);
            aggregate.pending = result.getStatus() == Status.PENDING;
        }
    }

    private void onRunFinished(TestRunFinished event) {
        if (outputDir == null) {
            onRunStarted(new TestRunStarted(runStart));
        }
        if (disabled) {
            return;
        }
        List<TestResultModel> results = tests.values().stream()
                .sorted(Comparator.comparing((TestAggregate a) -> a.file).thenComparingInt(a -> a.line).thenComparing(a -> a.project))
                .map(this::buildTest)
                .toList();
        Stats stats = new Stats();
        results.forEach(t -> stats.add(t.category));
        long startTime = runStart.toEpochMilli();
        long duration = Math.max(0, event.getInstant().toEpochMilli() - startTime);
        boolean failed = stats.failed > 0 || stats.broken > 0 || event.getResult().getError() != null;

        RunSummary run = new RunSummary();
        run.startTime = startTime;
        run.duration = duration;
        run.status = failed ? "failed" : "passed";
        run.stats = stats;
        List<RunSummary> trend = new ArrayList<>(history.runs);
        trend.add(run);
        trend = tail(trend, MAX_RUNS);

        ReportData data = new ReportData();
        data.title = firstNonBlank(System.getProperty("report.title"), System.getenv("REPORT_TITLE"),
                ReportContext.title(), "API Test Report").strip();
        data.outputFolder = sources.relativize(outputDir);
        data.generatedAt = System.currentTimeMillis();
        data.startTime = startTime;
        data.endTime = startTime + duration;
        data.duration = duration;
        data.status = run.status;
        data.stats = stats;
        data.environment = environment(results);
        data.git = gitInfo();
        data.trend = trend;
        data.tests = results;
        data.suites = suites(results);
        data.sources = sources.readSources();

        Path index = outputDir.resolve("index.html");
        HistoryFile next = new HistoryFile();
        next.runs = trend;
        for (TestResultModel t : results) {
            List<TestHistoryEntry> entries = new ArrayList<>(t.history);
            entries.add(new TestHistoryEntry(startTime, t.category, t.duration));
            next.tests.put(t.id, tail(entries, MAX_TEST_HISTORY));
        }
        try {
            Files.writeString(index, HtmlReportWriter.build(data, mapper), StandardCharsets.UTF_8);
            mapper.writeValue(outputDir.resolve(HISTORY_FILE).toFile(), next);
        } catch (IOException e) {
            disable("could not write " + index + " (" + e + ")");
            return;
        }
        System.out.println("\nModern report: " + index.toUri() + "\n");
    }

    // ------------------------------------------------------------------ model building

    private TestResultModel buildTest(TestAggregate aggregate) {
        List<AttemptModel> attempts = aggregate.attempts;
        AttemptModel last = attempts.get(attempts.size() - 1);
        boolean failedBefore = attempts.stream().limit(attempts.size() - 1L).anyMatch(a -> "failed".equals(a.status));
        String status = "passed".equals(last.status) && failedBefore ? "flaky" : last.status;
        AttemptModel failing = "flaky".equals(status)
                ? attempts.stream().filter(a -> !a.errors.isEmpty()).findFirst().orElse(last) : last;
        boolean assertion = aggregate.assertionFailures.get(attempts.indexOf(failing));
        String category = switch (status) {
            case "passed", "flaky", "skipped" -> status;
            default -> failing.errors.isEmpty() || assertion ? "failed" : "broken";
        };
        String message = failing.errors.isEmpty() ? "" : failing.errors.get(0).message;
        boolean hasFailure = List.of("failed", "broken", "flaky").contains(category);

        TestResultModel t = new TestResultModel();
        t.id = aggregate.id;
        t.title = aggregate.title;
        t.suitePath = aggregate.suitePath;
        t.titlePath.add(aggregate.project);
        t.titlePath.add(aggregate.file);
        t.titlePath.addAll(aggregate.suitePath);
        t.titlePath.add(aggregate.title);
        t.project = aggregate.project;
        t.browser = aggregate.browser;
        t.file = aggregate.file;
        t.line = aggregate.line;
        t.column = aggregate.column;
        t.location = aggregate.file + ":" + aggregate.line;
        t.status = status;
        t.category = category;
        t.errorCategory = hasFailure ? ErrorFactory.category(status, message) : null;
        t.errorGroup = hasFailure ? ErrorFactory.normalize(message) : null;
        t.duration = last.duration;
        t.startTime = attempts.get(0).startTime;
        t.retries = attempts.size() - 1;
        t.workerIndex = last.workerIndex;
        t.tags = aggregate.tags;
        if (aggregate.pending) {
            t.annotations.add(new AnnotationModel("pending", "Step definition throws PendingException"));
        }
        if (aggregate.featureName != null) {
            t.annotations.add(new AnnotationModel("feature", aggregate.featureName));
        }
        t.attempts = attempts;
        t.history = history.tests.getOrDefault(aggregate.id, List.of());
        return t;
    }

    private List<SuiteNode> suites(List<TestResultModel> results) {
        Map<String, SuiteNode> nodes = new LinkedHashMap<>();
        List<SuiteNode> roots = new ArrayList<>();
        for (TestResultModel t : results) {
            String projectId = "project:" + t.project;
            SuiteNode parent = nodes.computeIfAbsent(projectId, id -> {
                SuiteNode node = new SuiteNode(id, t.project, "project");
                roots.add(node);
                return node;
            });
            String path = t.project + "\u203a" + t.file;
            parent = child(nodes, parent, "file:" + path, t.file, "file");
            for (String suite : t.suitePath) {
                path = path + "\u203a" + suite;
                parent = child(nodes, parent, "describe:" + path, suite, "describe");
            }
            SuiteNode leaf = new SuiteNode(t.id, t.title, "test");
            leaf.testId = t.id;
            parent.children.add(leaf);
        }
        return roots;
    }

    private static SuiteNode child(Map<String, SuiteNode> nodes, SuiteNode parent, String id, String name, String type) {
        return nodes.computeIfAbsent(id, key -> {
            SuiteNode node = new SuiteNode(key, name, type);
            parent.children.add(node);
            return node;
        });
    }

    private List<EnvironmentEntry> environment(List<TestResultModel> results) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        env.put("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        putIfKnown(env, "Playwright", mavenVersion("com.microsoft.playwright", "playwright"));
        putIfKnown(env, "Rest Assured", mavenVersion("io.rest-assured", "rest-assured"));
        env.put("Cucumber", mavenVersion("io.cucumber", "cucumber-core"));
        env.put("Browsers", unique(results.stream().map(t -> t.browser).toList()));
        env.put("Projects", unique(results.stream().map(t -> t.project).toList()));
        env.put("Workers", String.valueOf(Math.max(1, workers.size())));
        env.put("CI", System.getenv("CI") != null ? "true" : "false");
        env.putAll(ReportContext.environment());
        return env.entrySet().stream().map(e -> new EnvironmentEntry(e.getKey(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------ helpers

    private String hookTitle(HookTestStep hook) {
        String type = switch (hook.getHookType()) {
            case BEFORE -> "Before";
            case AFTER -> "After";
            case BEFORE_STEP -> "BeforeStep";
            case AFTER_STEP -> "AfterStep";
        };
        Matcher m = CODE_LOCATION.matcher(Objects.toString(hook.getCodeLocation(), ""));
        String method = m.matches() ? m.group(2) : "";
        return type + " hook" + (method.isEmpty() ? "" : ": " + method);
    }

    private String hookLocation(String codeLocation) {
        Matcher m = CODE_LOCATION.matcher(Objects.toString(codeLocation, ""));
        if (!m.matches()) {
            return null;
        }
        return sources.fileForClass(m.group(1)).map(file -> {
            int line = sources.methodLine(file, m.group(2));
            return line > 0 ? file + ":" + line + ":" + sources.column(file, line) : null;
        }).orElse(null);
    }

    private static String syntheticMessage(Status status, String subject) {
        return switch (status) {
            case UNDEFINED -> "Undefined step: " + subject + "\nImplement a matching step definition.";
            case AMBIGUOUS -> "Ambiguous step: " + subject + "\nMore than one step definition matches.";
            default -> "Failed: " + subject;
        };
    }

    private AttachmentModel toAttachment(String name, String mediaType, byte[] data) {
        String type = mediaType == null ? "application/octet-stream" : mediaType;
        String lower = type.toLowerCase();
        AttachmentModel model = new AttachmentModel();
        model.name = name;
        model.contentType = type;
        model.size = (long) data.length;
        if (lower.startsWith("image/")) {
            model.kind = "image";
        } else if (lower.startsWith("video/")) {
            model.kind = "video";
        } else if (lower.equals("application/zip") && name.toLowerCase().contains("trace")) {
            model.kind = "trace";
        } else if (lower.startsWith("text/") || lower.matches(".*[/+](json|xml)\\b.*")) {
            model.kind = "text";
        } else {
            model.kind = "file";
        }
        if ("image".equals(model.kind) && INLINE_IMAGE.matcher(lower).matches() && data.length <= MAX_INLINE_IMAGE_BYTES) {
            model.src = "data:" + lower + ";base64," + Base64.getEncoder().encodeToString(data);
            return model;
        }
        String extension = EXTENSIONS.getOrDefault(lower.split(";")[0].strip(), "bin");
        String fileName = shortHash(data) + "-" + sanitize(name) + (sanitize(name).endsWith("." + extension) ? "" : "." + extension);
        try {
            Files.write(dataDir.resolve(fileName), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        model.src = "data/" + fileName;
        if ("text".equals(model.kind) && data.length <= MAX_INLINE_TEXT_BYTES) {
            model.content = new String(data, StandardCharsets.UTF_8);
        }
        return model;
    }

    private String buildPrompt(TestAggregate test, AttemptModel attempt) {
        if (attempt.errors.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of(
                "# Instructions", "",
                "- Following Java + Cucumber test failed (" + (isApiRun() ? "Rest Assured API test" : "Playwright UI test") + ").",
                "- Explain why, be concise, respect " + (isApiRun() ? "REST API testing" : "Playwright") + " best practices.",
                "- Provide a snippet of code with the fix, if possible.", "",
                "# Test info", "",
                "- Name: " + String.join(" >> ", test.suitePath) + " >> " + test.title,
                "- Location: " + test.file + ":" + test.line,
                "- Project: " + test.project + " (" + test.browser + ")",
                "- Attempt: " + (attempt.retry == 0 ? "first run" : "retry #" + attempt.retry),
                "- Status: " + attempt.status + ", after " + attempt.duration + "ms", "",
                "# Error details"));
        for (ErrorModel e : attempt.errors) {
            lines.addAll(List.of("", "```", e.message, "```"));
        }
        String scenario = sources.codeFrame(test.file, test.line, 20, false);
        if (scenario != null) {
            lines.addAll(List.of("", "# Scenario source", "", "```gherkin", scenario, "```"));
        }
        ErrorModel first = attempt.errors.get(0);
        if (first.location != null) {
            String[] parts = first.location.split(":");
            String frame = sources.codeFrame(parts[0], Integer.parseInt(parts[1]), 25, false);
            if (frame != null) {
                lines.addAll(List.of("", "# Failing code (" + parts[0] + ")", "", "```java", frame, "```"));
            }
        }
        attempt.attachments.stream()
                .filter(a -> a.content != null && a.name.toLowerCase().contains("snapshot"))
                .findFirst()
                .ifPresent(a -> lines.addAll(List.of("", "# Page snapshot", "", "```yaml", a.content.strip(), "```")));
        List<Map.Entry<StepModel, Integer>> steps = new ArrayList<>();
        flatten(attempt.steps, 0, steps);
        steps.removeIf(e -> "test.attach".equals(e.getKey().category));
        if (!steps.isEmpty()) {
            lines.addAll(List.of("", "# Executed steps", "", "```"));
            for (Map.Entry<StepModel, Integer> entry : steps.subList(0, Math.min(steps.size(), MAX_PROMPT_STEPS))) {
                StepModel s = entry.getKey();
                lines.add("  ".repeat(entry.getValue()) + ("failed".equals(s.status) ? "✘ " : "✓ ") + s.title
                        + " (" + s.duration + "ms)" + (s.location != null ? " — " + s.location : ""));
            }
            if (steps.size() > MAX_PROMPT_STEPS) {
                lines.add("… " + (steps.size() - MAX_PROMPT_STEPS) + " more steps");
            }
            lines.add("```");
        }
        String output = attempt.stdout.strip();
        if (!output.isEmpty()) {
            lines.addAll(List.of("", "# Console output", "", "```",
                    output.substring(Math.max(0, output.length() - MAX_PROMPT_OUTPUT)), "```"));
        }
        return String.join("\n", lines);
    }

    private static void flatten(List<StepModel> steps, int depth, List<Map.Entry<StepModel, Integer>> out) {
        for (StepModel s : steps) {
            out.add(Map.entry(s, depth));
            flatten(s.steps, depth + 1, out);
        }
    }

    private HistoryFile readHistory(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                HistoryFile parsed = mapper.readValue(file.toFile(), HistoryFile.class);
                if (parsed.version == 1 && parsed.runs != null && parsed.tests != null) {
                    return parsed;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // First run, or the history file is unreadable: start fresh.
        }
        return new HistoryFile();
    }

    private GitInfo gitInfo() {
        String commit = git("rev-parse", "--short", "HEAD");
        if (commit.isEmpty()) {
            return null;
        }
        GitInfo info = new GitInfo();
        info.commit = commit;
        info.branch = firstNonBlank(System.getenv("GITHUB_HEAD_REF"), System.getenv("GITHUB_REF_NAME"),
                git("rev-parse", "--abbrev-ref", "HEAD"));
        info.author = git("log", "-1", "--format=%an");
        info.message = git("log", "-1", "--format=%s");
        return info;
    }

    private String git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(sources.baseDir().toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return "";
            }
            return new String(out, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Environment rows for libraries that are not on the classpath ("unknown") are left out. */
    private static void putIfKnown(Map<String, String> env, String name, String version) {
        if (!"unknown".equals(version)) {
            env.put(name, version);
        }
    }

    /** True when Rest Assured is on the classpath and Playwright is not: prompts then talk about API tests. */
    private static boolean isApiRun() {
        return "unknown".equals(mavenVersion("com.microsoft.playwright", "playwright"))
                && !"unknown".equals(mavenVersion("io.rest-assured", "rest-assured"));
    }

    private static String mavenVersion(String group, String artifact) {
        String resource = "/META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (InputStream in = ModernCucumberReporter.class.getResourceAsStream(resource)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown";
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> List<T> tail(List<T> list, int max) {
        return new ArrayList<>(list.subList(Math.max(0, list.size() - max), list.size()));
    }

    private static String unique(Collection<String> values) {
        return String.join(", ", new LinkedHashSet<>(values.stream().filter(v -> v != null && !v.isBlank()).toList()));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        String clean = name.replaceAll("[^\\w.-]+", "_");
        clean = clean.length() > 80 ? clean.substring(0, 80) : clean;
        return clean.isEmpty() ? "attachment" : clean;
    }

    private static String shortHash(String input) {
        return shortHash(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String shortHash(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(input)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ internal state

    /** One execution (attempt) of a scenario. */
    private static final class Execution {
        final AttemptModel attempt = new AttemptModel();
        final List<StepModel> steps = new ArrayList<>();
        final StringBuilder stdout = new StringBuilder();
        StepModel current;
        StepModel failedStep;

        Execution(long startTime, int worker) {
            attempt.startTime = startTime;
            attempt.workerIndex = worker;
            attempt.parallelIndex = worker;
        }
    }

    /** All attempts of one scenario (per project), i.e. one row in the report. */
    private static final class TestAggregate {
        final String id;
        final String title;
        final String file;
        final int line;
        final int column;
        final String project;
        final String browser;
        final String featureName;
        final List<String> suitePath = new ArrayList<>();
        final List<String> tags;
        final List<AttemptModel> attempts = new ArrayList<>();
        final List<Boolean> assertionFailures = new ArrayList<>();
        boolean pending;

        TestAggregate(String id, TestCase testCase, FeatureInfo feature, String project, String browser) {
            this.id = id;
            this.project = project;
            this.browser = browser;
            this.line = testCase.getLocation().getLine();
            this.file = feature != null ? feature.file : testCase.getUri().toString();
            this.column = feature != null ? feature.column(line) : 1;
            this.featureName = feature != null ? feature.name : null;
            this.title = feature != null ? feature.title(testCase.getName(), line) : testCase.getName();
            if (feature != null) {
                suitePath.add(feature.name);
                String rule = feature.ruleAt(line);
                if (rule != null) {
                    suitePath.add(rule);
                }
            }
            this.tags = new ArrayList<>(new LinkedHashSet<>(testCase.getTags()));
        }
    }

    /** Minimal Gherkin source facts: feature name, rules and example rows. */
    private static final class FeatureInfo {
        private static final Pattern FEATURE = Pattern.compile("^\\s*(Feature|Ability|Business Need):\\s*(.*)$");
        private static final Pattern RULE = Pattern.compile("^\\s*Rule:\\s*(.*)$");

        final String file;
        final String name;
        final List<String> lines;
        final Map<Integer, String> rules = new LinkedHashMap<>();

        private FeatureInfo(String file, String name, List<String> lines) {
            this.file = file;
            this.name = name;
            this.lines = lines;
        }

        static FeatureInfo parse(String file, String source) {
            List<String> lines = source.lines().toList();
            String name = null;
            for (String line : lines) {
                Matcher feature = FEATURE.matcher(line);
                if (feature.matches()) {
                    name = feature.group(2).strip();
                    break;
                }
            }
            FeatureInfo info = new FeatureInfo(file, name == null || name.isEmpty() ? Paths.get(file).getFileName().toString() : name, lines);
            for (int i = 0; i < lines.size(); i++) {
                Matcher rule = RULE.matcher(lines.get(i));
                if (rule.matches()) {
                    info.rules.put(i + 1, rule.group(1).strip());
                }
            }
            return info;
        }

        String ruleAt(int line) {
            String found = null;
            for (Map.Entry<Integer, String> rule : rules.entrySet()) {
                if (rule.getKey() < line) {
                    found = rule.getValue();
                }
            }
            return found;
        }

        int column(int line) {
            if (line < 1 || line > lines.size()) {
                return 1;
            }
            String text = lines.get(line - 1);
            return text.length() - text.stripLeading().length() + 1;
        }

        /** Scenario name, plus the example values for Scenario Outline rows. */
        String title(String scenarioName, int line) {
            if (line < 1 || line > lines.size() || !lines.get(line - 1).strip().startsWith("|")) {
                return scenarioName;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : lines.get(line - 1).strip().split("\\|")) {
                if (!cell.isBlank()) {
                    cells.add(cell.strip());
                }
            }
            boolean alreadyInName = !cells.isEmpty() && cells.stream().allMatch(scenarioName::contains);
            return alreadyInName || cells.isEmpty() ? scenarioName : scenarioName + " [" + String.join(", ", cells) + "]";
        }
    }
}
