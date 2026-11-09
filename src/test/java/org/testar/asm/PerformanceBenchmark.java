package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.testar.evidence.ActionDescription;
import org.testar.evidence.ActionExecution;
import org.testar.evidence.ActionExecutionResult;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.ActionOutcome;
import org.testar.evidence.AvailableAction;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.CaptureScope;
import org.testar.evidence.Event;
import org.testar.evidence.EvidenceEvent;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.JsonlStore;
import org.testar.evidence.Manifest;
import org.testar.evidence.Observation;
import org.testar.evidence.Properties;
import org.testar.evidence.ReconstructedEvidence;
import org.testar.evidence.RecordedState;
import org.testar.evidence.TestRun;
import org.testar.evidence.TestSequence;
import org.testar.evidence.Widget;

/** Compare evidence workloads and growing ASM updates using one deterministic GUI trace. */
public final class PerformanceBenchmark {
    private PerformanceBenchmark() {
    }

    /** All options are optional; warm-up samples are saved but excluded from medians. */
    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = options(arguments);
        int actions = positive(options, "actions", 20);
        int widgets = positive(options, "widgets", 100);
        int properties = Integer.parseInt(options.getOrDefault("properties", "10"));
        int repeats = positive(options, "repeats", 3);
        int warmups = Integer.parseInt(options.getOrDefault("warmups", "2"));
        if (properties < 0 || properties > 32 || warmups < 0 || ((long) actions + 1) * widgets > 100000) {
            throw new IllegalArgumentException("Use properties 0..32, nonnegative warmups, and at most 100000 captured widgets across observations.");
        }
        String label = options.getOrDefault("label", "measurement").replaceAll("[^A-Za-z0-9_-]", "-");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        Path output = Path.of(options.getOrDefault("output", "target/benchmarks"))
            .resolve(timestamp + "-" + label + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(output);
        List<Map<String, Object>> samples = new ArrayList<>();
        List<Map<String, Double>> measured = new ArrayList<>();
        String expectedModelHash = null;
        for (int index = 0; index < warmups + repeats; index++) {
            Map<String, Object> sample = sample(output.resolve("sample-" + (index + 1)), actions, widgets, properties);
            sample.put("warmup", index < warmups);
            samples.add(sample);
            @SuppressWarnings("unchecked")
            Map<String, Double> seconds = (Map<String, Double>) sample.get("seconds");
            if (index >= warmups) {
                measured.add(seconds);
            }
            String modelHash = (String) sample.get("model_hash");
            check(expectedModelHash == null || expectedModelHash.equals(modelHash), "Identical traces produced different models.");
            expectedModelHash = modelHash;
            System.out.printf("%s sample %d: changed updates %.3fs; unchanged updates %.3fs%n",
                index < warmups ? "Warm-up" : "Measured", index + 1,
                seconds.get("asm_changed_updates"), seconds.get("asm_unchanged_updates"));
            Files.writeString(output.resolve("samples.json"), EvidenceJson.write(EvidenceJson.tree(samples)));
        }
        Map<String, Double> medians = new LinkedHashMap<>();
        for (String phase : measured.get(0).keySet()) {
            List<Double> values = measured.stream().map(item -> item.get(phase)).sorted().toList();
            int middle = values.size() / 2;
            medians.put(phase, values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle));
        }
        Map<String, Object> settings = Map.of("actions", actions, "widgets", widgets, "properties", properties,
            "repeats", repeats, "warmups", warmups);
        Map<String, Object> result = Map.of("label", label, "settings", settings, "samples", samples,
            "medians", medians, "java", System.getProperty("java.version"), "os", System.getProperty("os.name"));
        Files.writeString(output.resolve("results.json"), EvidenceJson.write(EvidenceJson.tree(result)));
        JsonNode previous = options.containsKey("compare") ? EvidenceJson.parse(Files.readString(Path.of(options.get("compare")))) : null;
        if (previous != null) {
            for (String setting : List.of("actions", "widgets", "properties")) {
                check(previous.path("settings").path(setting).asInt() == ((Number) settings.get(setting)).intValue(),
                    "Comparison requires the same action, widget, and property counts.");
            }
            for (JsonNode sample : previous.path("samples")) {
                check(expectedModelHash.equals(sample.path("model_hash").asText()),
                    "Compared traces produced different abstract models.");
            }
        }
        System.out.printf("%n%-30s %12s %12s%n", "Independent workload", previous == null ? "" : "Before (s)", "Median (s)");
        for (var entry : medians.entrySet()) {
            String before = previous == null ? "" : String.format("%.6f", previous.path("medians").path(entry.getKey()).asDouble());
            System.out.printf("%-30s %12s %12.6f%n", entry.getKey(), before, entry.getValue());
        }
        System.out.println("Results and validated traces: " + output.toAbsolutePath());
    }

    /** Each workload is measured separately; write/read timings include their own validation and conversion. */
    static Map<String, Object> sample(Path output, int actions, int widgets, int properties) throws Exception {
        Files.createDirectories(output);
        Map<String, Double> seconds = new LinkedHashMap<>();
        List<EvidenceEvent> events = timed(seconds, "evidence_construct", () -> trace(actions, widgets, properties));
        long characters = timed(seconds, "evidence_encode", () -> {
            long count = 0;
            for (EvidenceEvent event : events) {
                count += EvidenceJson.writeEvent(event).length();
            }
            return count;
        });
        timed(seconds, "evidence_apply", () -> {
            ReconstructedEvidence history = new ReconstructedEvidence(new Manifest("benchmark"));
            for (EvidenceEvent event : events) {
                history.apply(event);
            }
            check(history.observations().size() == actions + 1, "Missing observation.");
            return history;
        });
        Path directory = output.resolve("evidence");
        timed(seconds, "evidence_durable_write", () -> {
            try (JsonlStore store = JsonlStore.create(directory, new Manifest("benchmark"))) {
                for (EvidenceEvent event : events) {
                    store.record(event);
                }
            }
            return null;
        });
        ReconstructedEvidence read = timed(seconds, "evidence_read", () -> EvidenceReader.read(directory));
        check(read.observations().size() == actions + 1 && read.actionExecutions().size() == actions
            && read.recordedTransitions().size() == actions, "Trace record counts changed.");
        check(read.actionExecutions().stream().allMatch(record -> record.status() == ActionExecutionStatus.COMPLETED),
            "An execution did not complete.");
        ReconstructedEvidence history = new ReconstructedEvidence(new Manifest("benchmark"));
        List<JsonNode> snapshots = new ArrayList<>();
        for (EvidenceEvent event : events) {
            history.apply(event);
            if (event.event() instanceof Event.TransitionRecorded ||
                    event.event() instanceof Event.ObservationRecorded observation && observation.data().observationIndex().signum() == 0) {
                snapshots.add(timed(seconds, "source_prepare", () -> EvidenceSources.fromEvidence(history, "source")));
            }
        }
        JsonNode configuration = EvidenceJson.parse("""
            {"abstraction":{"version":1,"name":"Page and button","state":[{"name":"page","keys":["ui.title"],"required":true}],
            "capture":"ignore","actions":{"target":[{"name":"name","keys":["ui.name"],"required":true}]}},
            "asms":[{"name":"unvisited","action_limit":100000},{"name":"random","action_limit":100000}]}
            """);
        ArrayNode selectedRuns = Json.array(List.of(Json.object("source", "source", "run", "run")));
        AsmRun run = new AsmRun(configuration, Json.array(List.of()), Json.array(List.of()), size -> 0);
        for (int index = 0; index < snapshots.size(); index++) {
            ArrayNode sources = Json.array(List.of(snapshots.get(index)));
            String id = "update-" + index;
            JsonNode changed = timed(seconds, "asm_changed_updates", () -> run.updateEvidence(id, sources, selectedRuns));
            check(changed.path("changed").asBoolean(), "New evidence was ignored.");
            JsonNode unchanged = timed(seconds, "asm_unchanged_updates", () -> run.updateEvidence(id + "-same", sources, selectedRuns));
            check(!unchanged.path("changed").asBoolean(), "Identical evidence changed the revision.");
            JsonNode context = Json.object("context_id", "context-" + index,
                "observation", Json.object("source", "source", "run", "run", "observation", "o:" + index),
                "choices", List.of(Json.object("choice_id", "click", "action_description", "click", "target_widget", "widget-0")));
            timed(seconds, "context_and_selection", () -> {
                run.setContext(context);
                check(run.select().has("choice_id"), "The ASM could not select an available action.");
                return null;
            });
        }
        String model = run.model().toString();
        Files.writeString(output.resolve("model.json"), model);
        return new LinkedHashMap<>(Map.of("seconds", seconds, "events", events.size(), "observations", actions + 1,
            "executions", actions, "encoded_characters", characters, "model_hash", hash(model)));
    }

    /** Build actions between observations; each state captures fresh widgets and property values. */
    static List<EvidenceEvent> trace(int actions, int widgets, int properties) {
        List<EvidenceEvent> events = new ArrayList<>();
        add(events, new Event.TestRunStarted(new TestRun("run", "benchmark", "run", Properties.empty())));
        add(events, new Event.TestSequenceStarted(new TestSequence("sequence", "run", 1, "sequence", Properties.empty())));
        add(events, new Event.ActionDescribed(new ActionDescription("click", "click", Properties.empty())));
        add(events, new Event.ObservationRecorded(observation(0, widgets, properties)));
        for (int index = 1; index <= actions; index++) {
            add(events, new Event.ActionSelected(new ActionExecution("e:" + index, "sequence", "o:" + (index - 1),
                "click", "widget-0", Properties.empty())));
            add(events, new Event.ActionExecutionReported(new ActionExecutionResult("e:" + index, ActionOutcome.RETURNED, null)));
            add(events, new Event.ObservationRecorded(observation(index, widgets, properties)));
            add(events, new Event.TransitionRecorded("e:" + index, "o:" + index));
        }
        add(events, new Event.TestSequenceFinished("sequence", "completed"));
        add(events, new Event.TestRunFinished("run", "completed"));
        return events;
    }

    private static Observation observation(int index, int count, int properties) {
        List<Widget> widgets = new ArrayList<>();
        for (int widget = 0; widget < count; widget++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ui.name", "Widget " + widget);
            for (int property = 0; property < properties; property++) {
                values.put("example.property." + property, "Widget " + widget + " value " + property);
            }
            widgets.add(new Widget("widget-" + widget, null, Properties.of(values)));
        }
        RecordedState state = new RecordedState("state:" + index, Properties.of(Map.of("ui.title", "Page " + index % 5)),
            widgets, CaptureScope.FILTERED);
        return new Observation("o:" + index, "sequence", index, index, state,
            List.of(new AvailableAction("click", "widget-0")), AvailableActionScope.ELIGIBLE, List.of());
    }

    private static void add(List<EvidenceEvent> events, Event event) {
        events.add(new EvidenceEvent("event:" + events.size(), event));
    }

    private static <T> T timed(Map<String, Double> seconds, String phase, Callable<T> operation) throws Exception {
        long started = System.nanoTime();
        try {
            return operation.call();
        } finally {
            seconds.merge(phase, (System.nanoTime() - started) / 1_000_000_000.0, Double::sum);
        }
    }

    private static int positive(Map<String, String> options, String name, int fallback) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static Map<String, String> options(String[] arguments) {
        List<String> allowed = List.of("actions", "widgets", "properties", "warmups", "repeats", "label", "output", "compare");
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 == arguments.length || !arguments[index].startsWith("--") || !allowed.contains(arguments[index].substring(2))) {
                throw new IllegalArgumentException("Use --actions, --widgets, --properties, --warmups, --repeats, --label, --output, or --compare with a value.");
            }
            result.put(arguments[index].substring(2), arguments[index + 1]);
        }
        return result;
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
