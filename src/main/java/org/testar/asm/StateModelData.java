package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testar.abstraction.Abstraction;
import org.testar.abstraction.AbstractionLimitException;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.Manifest;
import org.testar.evidence.ReconstructedEvidence;
import static org.testar.asm.Json.*;

/**
 * Holds the recorded states and action executions accepted from evidence traces.
 * The abstraction library groups them into states and actions that the ASM can use
 * across different visits and trace sources. Updates preserve already accepted facts.
 */
final class StateModelData {
    static final List<String> STATUSES = List.of("completed", "failed", "incomplete",
        "awaiting_execution_result", "awaiting_resulting_observation");
    static final Set<String> PENDING = Set.of("awaiting_execution_result", "awaiting_resulting_observation");
    final JsonNode snapshot;
    final JsonNode model;
    final Map<String, JsonNode> sources = new LinkedHashMap<>();
    final Set<List<String>> runs = new HashSet<>();
    final Map<List<String>, JsonNode> observations = new HashMap<>();
    final Map<List<String>, JsonNode> executions = new HashMap<>();
    final Map<List<String>, JsonNode> sequences = new HashMap<>();
    final Map<List<String>, JsonNode> runRecords = new HashMap<>();

    /** Validate trace data, index records from selected runs, and build their abstract model. */
    StateModelData(JsonNode inputSources, JsonNode selectedRuns, JsonNode rules) {
        try {
            snapshot = normalize(inputSources, selectedRuns);
            for (JsonNode source : snapshot.get("sources")) {
                require(sources.put(text(source, "id"), source) == null, "invalid_evidence", "Repeated source identity.");
                validateSource(source);
            }
            for (JsonNode reference : selectedRuns) {
                var runKey = List.of(text(reference, "source"), text(reference, "run"));
                require(runs.add(runKey), "invalid_evidence", "Repeated selected run.");
                JsonNode source = sources.get(runKey.get(0));
                require(source != null, "invalid_evidence", "Unknown selected source.");
                JsonNode evidence = source.get("evidence");
                JsonNode run = list(evidence.get("runs")).stream()
                    .filter(item -> text(item, "id").equals(runKey.get(1))).findFirst().orElse(null);
                require(run != null, "invalid_evidence", "Unknown selected run.");
                runRecords.put(runKey, run);
                for (JsonNode sequence : evidence.get("sequences")) {
                    if (text(sequence, "test_run").equals(runKey.get(1))) {
                        sequences.put(List.of(runKey.get(0), text(sequence, "id")), sequence);
                        for (JsonNode observation : evidence.get("observations")) {
                            if (text(observation, "test_sequence").equals(text(sequence, "id"))) {
                                observations.put(List.of(runKey.get(0), runKey.get(1), text(observation, "id")), observation);
                            }
                        }
                        for (JsonNode record : evidence.get("executions")) {
                            if (text(record.get("execution"), "test_sequence").equals(text(sequence, "id"))) {
                                executions.put(List.of(runKey.get(0), runKey.get(1), text(record.get("execution"), "id")), record);
                            }
                        }
                    }
                }
            }
            JsonNode sourceArray = snapshot.get("sources");
            model = Abstraction.buildModel(Abstraction.preview(sourceArray, rules, selectedRuns), sourceArray);
        } catch (AbstractionLimitException error) {
            throw new AsmException("processing_limit", error.getMessage());
        } catch (AsmException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AsmException("invalid_evidence", error.getMessage());
        }
    }

    /** Order copied records consistently so a different input order does not look like new evidence. */
    static JsonNode normalize(JsonNode sourceArray, JsonNode selectedRuns) {
        var ordered = new ArrayList<JsonNode>();
        for (JsonNode source : list(sourceArray)) {
            ObjectNode copy = source.deepCopy();
            copy.get("evidence").fields().forEachRemaining(entry -> {
                List<JsonNode> records = list(entry.getValue());
                records.sort(Comparator.comparing(item -> item.has("id") ? text(item, "id") :
                    text(item.get("execution"), "id"), TEXT));
                ((ObjectNode) copy.get("evidence")).set(entry.getKey(), array(records));
            });
            ordered.add(copy);
        }
        ordered.sort(Comparator.comparing(item -> text(item, "id"), TEXT));
        List<JsonNode> selected = list(selectedRuns);
        selected.sort(Comparator.comparing(item -> List.of(text(item, "source"), text(item, "run")), KEYS));
        return object("sources", ordered, "selected_runs", selected);
    }

    /** Separate the original run or sequence description from its later completion information. */
    private static ObjectNode withoutLifecycle(JsonNode record) {
        ObjectNode result = record.deepCopy();
        result.remove(List.of("status", "reason"));
        return result;
    }

    /** Check source records through temporary in-memory Evidence Events; these events are never written to traces. */
    private static final class Validator {
        final ReconstructedEvidence history;
        int events;

        Validator(String id) {
            history = new ReconstructedEvidence(new Manifest(id));
        }

        void apply(String type, JsonNode data) {
            JsonNode event = object("schema_version", 3, "id", Integer.toString(++events),
                "event", object("event_type", type, "data", data));
            history.apply(EvidenceJson.readEvent(event.toString()));
        }
    }

    /** Supply a reason for temporary validation when a snapshot omits it; retain the original snapshot unchanged. */
    private static String finishReason(JsonNode record) {
        return record.path("reason").isTextual() ? text(record, "reason") : "Reconstructed lifecycle finished";
    }

    /** Check that states, actions, execution results, and transitions form a valid evidence history. */
    private static void validateSource(JsonNode source) {
        JsonNode evidence = source.get("evidence");
        Validator validator = new Validator(text(source, "id"));
        for (JsonNode action : evidence.get("actions")) {
            validator.apply("action_described", action);
        }
        for (JsonNode run : evidence.get("runs")) {
            validator.apply("test_run_started", withoutLifecycle(run));
            List<JsonNode> sequences = list(evidence.get("sequences")).stream()
                .filter(item -> text(item, "test_run").equals(text(run, "id")))
                .sorted(Comparator.comparing(item -> item.path("sequence_index").bigIntegerValue())).toList();
            for (JsonNode sequence : sequences) {
                validator.apply("test_sequence_started", withoutLifecycle(sequence));
                List<JsonNode> observations = list(evidence.get("observations")).stream()
                    .filter(item -> text(item, "test_sequence").equals(text(sequence, "id")))
                    .sorted(Comparator.comparing(item -> item.path("observation_index").bigIntegerValue())).toList();
                List<JsonNode> records = list(evidence.get("executions")).stream()
                    .filter(item -> text(item.get("execution"), "test_sequence").equals(text(sequence, "id"))).toList();
                for (JsonNode observation : observations) {
                    validator.apply("observation_recorded", observation);
                    for (JsonNode record : records) {
                        if (text(record, "resultingObservation").equals(text(observation, "id"))) {
                            validator.apply("transition_recorded", object("action_execution", record.get("execution").get("id"),
                                "resulting_observation", observation.get("id")));
                        }
                    }
                    for (JsonNode record : records) {
                        if (text(record.get("execution"), "source_observation").equals(text(observation, "id"))) {
                            validator.apply("action_selected", record.get("execution"));
                            if (!record.get("executionResult").isNull()) {
                                validator.apply("action_execution_reported", record.get("executionResult"));
                            }
                        }
                    }
                }
                if (text(sequence, "status").equals("finished")) {
                    validator.apply("test_sequence_finished", object("test_sequence", sequence.get("id"), "reason", finishReason(sequence)));
                } else {
                    require(text(sequence, "status").equals("running"), "invalid_evidence", "Unknown sequence status.");
                }
            }
            if (text(run, "status").equals("finished")) {
                validator.apply("test_run_finished", object("test_run", run.get("id"), "reason", finishReason(run)));
            } else {
                require(text(run, "status").equals("running"), "invalid_evidence", "Unknown run status.");
            }
        }
        require(validator.history.observations().size() == evidence.get("observations").size() &&
            validator.history.testSequences().size() == evidence.get("sequences").size() &&
            validator.history.actionExecutions().size() == evidence.get("executions").size(),
            "invalid_evidence", "Unresolved snapshot records.");
        for (JsonNode record : evidence.get("executions")) {
            var actual = validator.history.actionExecution(text(record.get("execution"), "id")).orElseThrow();
            String status = actual.status().name().toLowerCase(java.util.Locale.ROOT);
            require(status.equals(text(record, "status")), "invalid_evidence", "Execution status disagrees with lifecycle.");
        }
    }

    /** Accept additional evidence while preserving existing facts; reuse this model if the data has not changed. */
    StateModelData extended(JsonNode newSources, JsonNode selectedRuns, JsonNode rules) {
        Set<List<String>> selected = new HashSet<>();
        selectedRuns.forEach(item -> selected.add(List.of(text(item, "source"), text(item, "run"))));
        require(selected.containsAll(runs), "conflicting_evidence", "Selected runs cannot be removed.");
        Set<String> identities = new HashSet<>();
        for (JsonNode source : newSources) {
            require(identities.add(text(source, "id")), "invalid_evidence", "Repeated source identity in update.");
        }
        var merged = new LinkedHashMap<>(sources);
        for (JsonNode source : newSources) {
            JsonNode previous = merged.get(text(source, "id"));
            if (previous != null) {
                checkExtension(previous, source);
            }
            merged.put(text(source, "id"), source);
        }
        JsonNode candidate = normalize(array(merged.values()), selectedRuns);
        if (same(candidate, snapshot)) {
            return this;
        }
        return new StateModelData(candidate.get("sources"), candidate.get("selected_runs"), rules);
    }

    /** Allow new records and execution progress while rejecting changes to facts already accepted. */
    private static void checkExtension(JsonNode previous, JsonNode incoming) {
        for (String name : List.of("runs", "sequences", "observations", "actions", "executions")) {
            Map<String, JsonNode> next = new HashMap<>();
            for (JsonNode record : incoming.get("evidence").get(name)) {
                String id = text(name.equals("executions") ? record.get("execution") : record, "id");
                require(next.put(id, record) == null, "conflicting_evidence", "Repeated record identity.");
            }
            for (JsonNode before : previous.get("evidence").get(name)) {
                String id = text(name.equals("executions") ? before.get("execution") : before, "id");
                JsonNode after = next.get(id);
                require(after != null, "conflicting_evidence", "An accepted record was removed.");
                if (name.equals("observations") || name.equals("actions")) {
                    require(same(before, after), "conflicting_evidence", "An immutable record changed.");
                } else if (name.equals("executions")) {
                    require(same(before.get("execution"), after.get("execution")), "conflicting_evidence", "Execution arguments changed.");
                    for (String field : List.of("executionResult", "resultingObservation")) {
                        require(before.get(field).isNull() || same(before.get(field), after.get(field)),
                            "conflicting_evidence", "An accepted execution result changed.");
                    }
                    require(PENDING.contains(text(before, "status")) || text(before, "status").equals(text(after, "status")),
                        "conflicting_evidence", "A final execution status changed.");
                    require(!text(before, "status").equals("awaiting_resulting_observation") ||
                        !text(after, "status").equals("awaiting_execution_result"), "conflicting_evidence", "Execution lifecycle regressed.");
                } else {
                    require(same(withoutLifecycle(before), withoutLifecycle(after)),
                        "conflicting_evidence", "Run or sequence content changed.");
                    require(!text(before, "status").equals("finished") || same(before, after),
                        "conflicting_evidence", "A finished lifecycle changed.");
                }
            }
        }
    }
}
