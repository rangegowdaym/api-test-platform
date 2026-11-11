package org.rg.reporter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON contract consumed by the report UI (ui/app.js). Field names mirror the
 * TypeScript reporter's types.ts so both reporters share one front-end.
 */
public final class ReportModels {

    private ReportModels() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorModel {
        public String message;
        public String stack;
        public String snippet;
        public String location;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepModel {
        public String title;
        /** test.step | pw:api | expect | hook | fixture | test.attach */
        public String category;
        public long startTime;
        public long duration;
        public String status = "passed";
        public ErrorModel error;
        public String location;
        public List<StepModel> steps = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AttachmentModel {
        public String name;
        public String contentType;
        /** image | video | trace | text | file */
        public String kind;
        public String src;
        public String content;
        public Long size;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AttemptModel {
        public int retry;
        /** passed | failed | timedOut | skipped | interrupted */
        public String status;
        public long startTime;
        public long duration;
        public int workerIndex;
        public int parallelIndex;
        public List<ErrorModel> errors = new ArrayList<>();
        public List<StepModel> steps = new ArrayList<>();
        public List<AttachmentModel> attachments = new ArrayList<>();
        public String stdout = "";
        public String stderr = "";
        public String prompt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnnotationModel {
        public String type;
        public String description;

        public AnnotationModel() {
        }

        public AnnotationModel(String type, String description) {
            this.type = type;
            this.description = description;
        }
    }

    public static class TestHistoryEntry {
        public long startTime;
        public String category;
        public long duration;

        public TestHistoryEntry() {
        }

        public TestHistoryEntry(long startTime, String category, long duration) {
            this.startTime = startTime;
            this.category = category;
            this.duration = duration;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestResultModel {
        public String id;
        public String title;
        public List<String> titlePath = new ArrayList<>();
        public List<String> suitePath = new ArrayList<>();
        public String project;
        public String browser;
        public String file;
        public int line;
        public int column;
        public String location;
        /** passed | failed | skipped | flaky | timedOut | interrupted */
        public String status;
        /** passed | failed | broken | skipped | flaky */
        public String category;
        public String errorCategory;
        public String errorGroup;
        public long duration;
        public long startTime;
        public int retries;
        public int workerIndex;
        public List<String> tags = new ArrayList<>();
        public List<AnnotationModel> annotations = new ArrayList<>();
        public List<AttemptModel> attempts = new ArrayList<>();
        public List<TestHistoryEntry> history = new ArrayList<>();
    }

    public static class Stats {
        public int total;
        public int passed;
        public int failed;
        public int broken;
        public int skipped;
        public int flaky;

        public void add(String category) {
            total++;
            switch (category) {
                case "passed" -> passed++;
                case "failed" -> failed++;
                case "broken" -> broken++;
                case "flaky" -> flaky++;
                default -> skipped++;
            }
        }
    }

    public static class RunSummary {
        public long startTime;
        public long duration;
        public String status;
        public Stats stats;
    }

    public static class HistoryFile {
        public int version = 1;
        public List<RunSummary> runs = new ArrayList<>();
        public Map<String, List<TestHistoryEntry>> tests = new LinkedHashMap<>();
    }

    public static class GitInfo {
        public String branch;
        public String commit;
        public String author;
        public String message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SuiteNode {
        public String id;
        public String name;
        /** project | file | describe | test */
        public String type;
        public String testId;
        public List<SuiteNode> children = new ArrayList<>();

        public SuiteNode() {
        }

        public SuiteNode(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    public static class EnvironmentEntry {
        public String name;
        public String value;

        public EnvironmentEntry() {
        }

        public EnvironmentEntry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReportData {
        public String title;
        public String outputFolder;
        public long generatedAt;
        public long startTime;
        public long endTime;
        public long duration;
        public String status;
        public Stats stats;
        public List<EnvironmentEntry> environment = new ArrayList<>();
        public GitInfo git;
        public List<RunSummary> trend = new ArrayList<>();
        public List<TestResultModel> tests = new ArrayList<>();
        public List<SuiteNode> suites = new ArrayList<>();
        public Map<String, String> sources = new LinkedHashMap<>();
    }
}
