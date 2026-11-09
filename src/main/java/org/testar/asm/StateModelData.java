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
    final Map<PreparedSnapshot.RecordKey, PreparedSnapshot.RecordContent> records;
    final Map<String, SourceValidation> validations = new HashMap<>();
    final JsonNode model;
    final Map<String, JsonNode> sources = new LinkedHashMap<>();
    final Set<List<String>> runs = new HashSet<>();
    final Map<List<String>, JsonNode> observations = new HashMap<>();
    final Map<List<String>, JsonNode> executions = new HashMap<>();
    final Map<List<String>, JsonNode> sequences = new HashMap<>();
    final Map<List<String>, JsonNode> runRecords = new HashMap<>();

    /** Validate trace data, index records from selected runs, and build their abstract model. */
    StateModelData(JsonNode inputSources, JsonNode selectedRuns, JsonNode rules) {
        this(new PreparedSnapshot(inputSources, selectedRuns, null), rules, null);
    }

    /** Build candidate data separately, preserving accepted data if any check fails. */
    private StateModelData(PreparedSnapshot prepared, JsonNode rules, StateModelData previous) {
        try {
            snapshot = prepared.snapshot;
            records = prepared.records;
            JsonNode selectedRuns = snapshot.get("selected_runs");
            for (JsonNode source : snapshot.get("sources")) {
                require(sources.put(text(source, "id"), source) == null, "invalid_evidence", "Repeated source identity.");
                SourceValidation old = previous == null ? null : previous.validations.get(text(source, "id"));
                validations.put(text(source, "id"), validateSource(source, old));
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

    /** Separate the original run or sequence description from its later completion information. */
    private static ObjectNode withoutLifecycle(JsonNode record) {
        ObjectNode result = record.deepCopy();
        result.remove(List.of("status", "reason"));
        return result;
    }

    /** Check source records through temporary in-memory Evidence Events; these events are never written to traces. */
    private static final class Validator {
        final ReconstructedEvidence history;
        Validator(ReconstructedEvidence history) {
            this.history = history;
        }

        void apply(String type, JsonNode data) {
            JsonNode event = object("schema_version", 3, "id", Integer.toString(history.eventCount() + 1),
                "event", object("event_type", type, "data", data));
            history.apply(EvidenceJson.readEvent(event.toString()));
        }
    }

    /** Supply a reason for temporary validation when a snapshot omits it; retain the original snapshot unchanged. */
    private static String finishReason(JsonNode record) {
        return record.path("reason").isTextual() ? text(record, "reason") : "Reconstructed lifecycle finished";
    }

    private record PlannedEvent(String type, JsonNode data) {
    }

    private record SourceValidation(ReconstructedEvidence history, List<PlannedEvent> events, Set<String> actionIds) {
    }

    /** Collect lifecycle order using references to owned records, without copying widgets. */
    private static final class Planner {
        final List<PlannedEvent> events = new ArrayList<>();

        void add(String type, JsonNode data) {
            events.add(new PlannedEvent(type, data));
        }
    }

    /** Check that states, actions, execution results, and transitions form a valid evidence history. */
    private static SourceValidation validateSource(JsonNode source, SourceValidation previous) {
        JsonNode evidence = source.get("evidence");
        Planner plan = new Planner();
        for (JsonNode run : evidence.get("runs")) {
            plan.add("test_run_started", withoutLifecycle(run));
            List<JsonNode> sequences = list(evidence.get("sequences")).stream()
                .filter(item -> text(item, "test_run").equals(text(run, "id")))
                .sorted(Comparator.comparing(item -> item.path("sequence_index").bigIntegerValue())).toList();
            for (JsonNode sequence : sequences) {
                plan.add("test_sequence_started", withoutLifecycle(sequence));
                List<JsonNode> observations = list(evidence.get("observations")).stream()
                    .filter(item -> text(item, "test_sequence").equals(text(sequence, "id")))
                    .sorted(Comparator.comparing(item -> item.path("observation_index").bigIntegerValue())).toList();
                List<JsonNode> records = list(evidence.get("executions")).stream()
                    .filter(item -> text(item.get("execution"), "test_sequence").equals(text(sequence, "id"))).toList();
                for (JsonNode observation : observations) {
                    plan.add("observation_recorded", observation);
                    for (JsonNode record : records) {
                        if (text(record, "resultingObservation").equals(text(observation, "id"))) {
                            plan.add("transition_recorded", object("action_execution", record.get("execution").get("id"),
                                "resulting_observation", observation.get("id")));
                        }
                    }
                    for (JsonNode record : records) {
                        if (text(record.get("execution"), "source_observation").equals(text(observation, "id"))) {
                            plan.add("action_selected", record.get("execution"));
                            if (!record.get("executionResult").isNull()) {
                                plan.add("action_execution_reported", record.get("executionResult"));
                            }
                        }
                    }
                }
                if (text(sequence, "status").equals("finished")) {
                    plan.add("test_sequence_finished", object("test_sequence", sequence.get("id"), "reason", finishReason(sequence)));
                } else {
                    require(text(sequence, "status").equals("running"), "invalid_evidence", "Unknown sequence status.");
                }
            }
            if (text(run, "status").equals("finished")) {
                plan.add("test_run_finished", object("test_run", run.get("id"), "reason", finishReason(run)));
            } else {
                require(text(run, "status").equals("running"), "invalid_evidence", "Unknown run status.");
            }
        }
        boolean extendsHistory = previous != null && plan.events.size() >= previous.events().size();
        if (extendsHistory) {
            for (int index = 0; index < previous.events().size(); index++) {
                PlannedEvent before = previous.events().get(index);
                PlannedEvent after = plan.events.get(index);
                extendsHistory &= before.type().equals(after.type()) && same(before.data(), after.data());
            }
        }
        Set<String> actionIds = new HashSet<>();
        List<JsonNode> newActions = new ArrayList<>();
        for (JsonNode action : evidence.get("actions")) {
            actionIds.add(text(action, "id"));
            if (!extendsHistory || !previous.actionIds().contains(text(action, "id"))) {
                newActions.add(action);
            }
        }
        boolean unchanged = extendsHistory && newActions.isEmpty() && plan.events.size() == previous.events().size();
        ReconstructedEvidence history = unchanged ? previous.history() : extendsHistory
            ? previous.history().snapshot() : new ReconstructedEvidence(new Manifest(text(source, "id")));
        Validator validator = new Validator(history);
        for (JsonNode action : newActions) {
            validator.apply("action_described", action);
        }
        int acceptedEvents = extendsHistory ? previous.events().size() : 0;
        for (PlannedEvent event : plan.events.subList(acceptedEvents, plan.events.size())) {
            validator.apply(event.type(), event.data());
        }
        // Check summaries even for an unchanged plan: unsupported status changes
        // and observations outside all sequences must still be rejected.
        require(validator.history.observations().size() == evidence.get("observations").size() &&
            validator.history.testSequences().size() == evidence.get("sequences").size() &&
            validator.history.actionExecutions().size() == evidence.get("executions").size(),
            "invalid_evidence", "Unresolved snapshot records.");
        for (JsonNode record : evidence.get("executions")) {
            var actual = validator.history.actionExecution(text(record.get("execution"), "id")).orElseThrow();
            String status = actual.status().name().toLowerCase(java.util.Locale.ROOT);
            require(status.equals(text(record, "status")), "invalid_evidence", "Execution status disagrees with lifecycle.");
        }
        return unchanged ? previous : new SourceValidation(history, List.copyOf(plan.events), Set.copyOf(actionIds));
    }

    /** Accept additional evidence while preserving existing facts; reuse this model if the data has not changed. */
    StateModelData extended(PreparedSnapshot prepared, JsonNode rules) {
        JsonNode newSources = prepared.snapshot.get("sources");
        JsonNode selectedRuns = prepared.snapshot.get("selected_runs");
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
        List<JsonNode> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(item -> text(item, "id"), TEXT));
        ObjectNode candidate = MAPPER.createObjectNode();
        candidate.set("sources", MAPPER.createArrayNode().addAll(ordered));
        candidate.set("selected_runs", selectedRuns);
        if (same(candidate, snapshot)) {
            return this;
        }
        Map<PreparedSnapshot.RecordKey, PreparedSnapshot.RecordContent> incoming = new HashMap<>(prepared.records);
        prepared.records.putAll(records);
        prepared.records.putAll(incoming);
        prepared.snapshot = candidate;
        return new StateModelData(prepared, rules, this);
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
