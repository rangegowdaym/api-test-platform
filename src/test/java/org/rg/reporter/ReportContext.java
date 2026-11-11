package org.rg.reporter;

import org.rg.reporter.model.ReportModels.StepModel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Supplier;

/**
 * Public API that test code uses to enrich the modern report:
 * nested steps (step / action / expect), per-thread project (browser) and environment entries.
 * All methods are safe to call when the reporter plugin is not active.
 */
public final class ReportContext {

    public static final String STEP = "test.step";
    public static final String ACTION = "pw:api";
    public static final String EXPECT = "expect";

    private static final Map<String, String> ENVIRONMENT = new LinkedHashMap<>();
    private static final Set<String> SKIPPED_FRAME_PREFIXES = new CopyOnWriteArraySet<>(List.of(
            ReportContext.class.getPackageName() + ".", "java.", "jdk.", "sun."));
    private static final ThreadLocal<String> PROJECT = new ThreadLocal<>();
    private static final ThreadLocal<String> BROWSER = new ThreadLocal<>();
    private static final ThreadLocal<Deque<StepModel>> STACK = new ThreadLocal<>();
    private static final ThreadLocal<StringBuilder> STDOUT = new ThreadLocal<>();

    private static volatile String title;
    private static volatile ErrorFactory errors;
    private static volatile SourceIndex sources;

    private ReportContext() {
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    // ---------------------------------------------------------------- environment / project

    /** Adds (or replaces) an entry of the Environment card. */
    public static void environment(String name, Object value) {
        synchronized (ENVIRONMENT) {
            ENVIRONMENT.put(name, value == null ? "" : String.valueOf(value));
        }
    }

    static Map<String, String> environment() {
        synchronized (ENVIRONMENT) {
            return new LinkedHashMap<>(ENVIRONMENT);
        }
    }

    /** Report title; -Dreport.title / REPORT_TITLE still take precedence. */
    public static void title(String value) {
        title = value;
    }

    static String title() {
        return title;
    }

    /** Project (report grouping, e.g. "chromium" or "qa-chromium") and browser for tests on this thread. */
    public static void project(String project, String browser) {
        PROJECT.set(project);
        BROWSER.set(browser);
    }

    static String currentProject() {
        return PROJECT.get();
    }

    static String currentBrowser() {
        return BROWSER.get();
    }

    /**
     * Classes whose frames should be ignored when computing a step location, e.g. a
     * project-specific wrapper around these methods.
     */
    public static void skipFramesFrom(String classOrPackagePrefix) {
        SKIPPED_FRAME_PREFIXES.add(classOrPackagePrefix);
    }

    static boolean isSkippedFrame(String className) {
        return SKIPPED_FRAME_PREFIXES.stream().anyMatch(className::startsWith);
    }

    /** Appends a line to the Console tab of the running test. */
    public static void log(String text) {
        StringBuilder out = STDOUT.get();
        if (out != null) {
            out.append(text).append('\n');
        }
    }

    // ---------------------------------------------------------------- nested steps

    public static void step(String title, ThrowingRunnable body) {
        run(title, STEP, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T step(String title, ThrowingSupplier<T> body) {
        return run(title, STEP, body);
    }

    /** A browser action, shown with the "action" icon (Playwright's pw:api). */
    public static void action(String title, ThrowingRunnable body) {
        run(title, ACTION, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T action(String title, ThrowingSupplier<T> body) {
        return run(title, ACTION, body);
    }

    /** An assertion, shown with the "expect" icon. */
    public static void expect(String title, ThrowingRunnable body) {
        run(title, EXPECT, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T run(String title, String category, ThrowingSupplier<T> body) {
        Deque<StepModel> stack = STACK.get();
        if (stack == null || stack.isEmpty()) {
            return invoke(body);
        }
        StepModel step = new StepModel();
        step.title = title;
        step.category = category;
        step.startTime = System.currentTimeMillis();
        step.location = callerLocation();
        stack.peek().steps.add(step);
        stack.push(step);
        try {
            return invoke(body);
        } catch (RuntimeException | Error e) {
            step.status = "failed";
            ErrorFactory factory = errors;
            if (factory != null) {
                step.error = factory.toError(e);
            }
            throw e;
        } finally {
            step.duration = System.currentTimeMillis() - step.startTime;
            stack.pop();
        }
    }

    private static <T> T invoke(ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static String callerLocation() {
        SourceIndex index = sources;
        if (index == null) {
            return null;
        }
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !isSkippedFrame(f.getClassName()))
                .map(StackWalker.StackFrame::toStackTraceElement)
                .map(index::locationOf)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElse(null));
    }

    // ---------------------------------------------------------------- plugin wiring

    static void install(SourceIndex index, ErrorFactory factory) {
        sources = index;
        errors = factory;
    }

    static void beginTest(StringBuilder stdout) {
        STDOUT.set(stdout);
    }

    static void endTest() {
        STDOUT.remove();
        STACK.remove();
    }

    static void beginStep(StepModel root) {
        Deque<StepModel> stack = new ArrayDeque<>();
        stack.push(root);
        STACK.set(stack);
    }

    static void endStep() {
        STACK.remove();
    }

    /** Current innermost step on this thread (used to nest attachment markers). */
    static StepModel currentStep() {
        Deque<StepModel> stack = STACK.get();
        return stack == null ? null : stack.peek();
    }
}
