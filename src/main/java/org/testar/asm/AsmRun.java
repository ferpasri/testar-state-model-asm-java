package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import org.testar.abstraction.Abstraction;
import static org.testar.asm.Json.*;

/**
 * Controls one exploration run: accept recorded states, ask the configured ASM to
 * select an available action, and update the model after execution. It also switches
 * between ASMs and enforces action limits; the exploration driver operates the GUI.
 */
public final class AsmRun {
    private final JsonNode configuration;
    private final JsonNode rules;
    private final IntUnaryOperator random;
    private StateModelData stateModelData;
    private long revision;
    private JsonNode context;
    private final Map<String, JsonNode> contexts = new HashMap<>();
    private JsonNode decision;
    private boolean cancelled;
    private List<String> activeExecutionReference;
    private final Map<List<String>, ActionExecutionContext> executionContexts = new HashMap<>();
    private final Map<String, JsonNode> updates = new HashMap<>();
    private final Set<List<String>> counted = new HashSet<>();
    private int asmIndex;
    private long entryCount;
    private long totalCount;

    /**
     * Remembers the selected action and its source observation while execution is
     * outstanding. Keeping the context afterwards lets repeated reports be recognized.
     */
    private static final class ActionExecutionContext {
        final JsonNode context;
        final JsonNode choice;
        Boolean invoked;

        ActionExecutionContext(JsonNode context, JsonNode choice) {
            this.context = context.deepCopy();
            this.choice = choice.deepCopy();
        }
    }

    /** Start exploration from the selected trace runs, using ordinary random choices for ties. */
    public AsmRun(JsonNode configuration, JsonNode sources, JsonNode selectedRuns) {
        this(configuration, sources, selectedRuns, size -> ThreadLocalRandom.current().nextInt(size));
    }

    /** Check the ASM configuration and build the initial model; chooseIndex can control random choices in tests. */
    public AsmRun(JsonNode configuration, JsonNode sources, JsonNode selectedRuns, IntUnaryOperator chooseIndex) {
        this.configuration = configuration.deepCopy();
        random = java.util.Objects.requireNonNull(chooseIndex);
        try {
            Set<String> allowed = Set.of("abstraction", "asms", "total_action_limit");
            configuration.fieldNames().forEachRemaining(field ->
                require(allowed.contains(field), "invalid_configuration", "Unknown configuration field."));
            rules = Abstraction.parseRules(configuration.get("abstraction").toString());
            require(rules.has("actions"), "invalid_configuration", "Action abstraction is required.");
            require(configuration.path("asms").isArray() && !configuration.get("asms").isEmpty(),
                "invalid_configuration", "Configure at least one ASM.");
            for (JsonNode asm : configuration.get("asms")) {
                String name = text(asm, "name");
                require(Set.of("unvisited", "random").contains(name), "invalid_configuration", "Unknown ASM.");
                positiveLimit(asm.get("action_limit"));
                Set<String> fields = name.equals("unvisited") ?
                    Set.of("name", "action_limit", "unmatched_choices") : Set.of("name", "action_limit");
                asm.fieldNames().forEachRemaining(field ->
                    require(fields.contains(field), "invalid_configuration", "Unsupported ASM option."));
                require(!asm.has("unmatched_choices") ||
                    Set.of("exclude", "isolated").contains(text(asm, "unmatched_choices")),
                    "invalid_configuration", "Unknown unmatched-choice policy.");
            }
            if (configuration.has("total_action_limit")) {
                positiveLimit(configuration.get("total_action_limit"));
            }
        } catch (AsmException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AsmException("invalid_configuration", error.getMessage());
        }
        stateModelData = new StateModelData(sources, selectedRuns, rules);
    }

    /** Require an action limit that allows at least one execution. */
    private static void positiveLimit(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.bigIntegerValue().signum() > 0,
            "invalid_configuration", "Action limits must be positive integers.");
    }

    /** Identify the accepted evidence update used by a selection, so outdated selections can be rejected. */
    public synchronized long asmDataRevision() {
        return revision;
    }

    /** Return a copy of the current abstract model that the caller may inspect without changing this run. */
    public synchronized JsonNode model() {
        return stateModelData.model.deepCopy();
    }

    /** Attach the current ASM data revision to a response so callers know which evidence it uses. */
    private ObjectNode result(String type, Object... fields) {
        ObjectNode value = object("result_type", type, "asm_data_revision", revision);
        value.setAll(object(fields));
        return value;
    }

    /** Discard the previous observation and selection when they can no longer guide the next action. */
    private void retireContext() {
        context = null;
        decision = null;
    }

    /** Accept the current observation and eligible available actions, checking that they exist in the evidence. */
    public synchronized JsonNode setContext(JsonNode input) {
        require(activeExecutionReference == null, "execution_in_progress", "Report the outstanding execution first.");
        JsonNode candidate = input.deepCopy();
        String id = text(candidate, "context_id");
        require(!id.isBlank(), "invalid_context", "A context ID is required.");
        if (contexts.containsKey(id)) {
            require(same(contexts.get(id), candidate), "context_conflict", "Context ID has different content.");
            require(context != null && text(context, "context_id").equals(id), "stale_context", "Context has been retired.");
            return result("context_ready", "context_id", id);
        }
        try {
            List<String> reference = key(candidate.get("observation"), "observation");
            JsonNode observation = stateModelData.observations.get(reference);
            require(observation != null, "invalid_context", "Unknown current observation.");
            JsonNode sequence = stateModelData.sequences.get(List.of(reference.get(0), text(observation, "test_sequence")));
            require(text(sequence, "status").equals("running") &&
                text(stateModelData.runRecords.get(reference.subList(0, 2)), "status").equals("running"),
                "invalid_context", "The observation belongs to a finished sequence or run.");
            for (var entry : stateModelData.observations.entrySet()) {
                JsonNode other = entry.getValue();
                if (entry.getKey().subList(0, 2).equals(reference.subList(0, 2)) &&
                        other.get("test_sequence").equals(observation.get("test_sequence"))) {
                    require(other.get("observation_index").bigIntegerValue().compareTo(
                        observation.get("observation_index").bigIntegerValue()) <= 0,
                        "invalid_context", "A newer observation exists.");
                }
            }
            for (var entry : stateModelData.executions.entrySet()) {
                JsonNode record = entry.getValue();
                require(!(entry.getKey().subList(0, 2).equals(reference.subList(0, 2)) &&
                    record.get("execution").get("test_sequence").equals(observation.get("test_sequence")) &&
                    StateModelData.PENDING.contains(text(record, "status"))),
                    "execution_in_progress", "Resolve the sequence's pending execution.");
            }
            Set<String> choices = new HashSet<>();
            for (JsonNode choice : candidate.get("choices")) {
                String choiceId = text(choice, "choice_id");
                require(!choiceId.isBlank() && choices.add(choiceId), "invalid_context", "Choice IDs must be unique.");
                JsonNode binding = object("action_description", choice.get("action_description"), "target_widget", choice.get("target_widget"));
                require(list(observation.get("available_actions")).stream().anyMatch(item -> same(item, binding)),
                    "invalid_context", "Choice was not reported at this observation.");
            }
        } catch (AsmException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AsmException("invalid_context", error.getMessage());
        }
        context = candidate;
        contexts.put(id, candidate.deepCopy());
        decision = null;
        return result("context_ready", "context_id", id);
    }

    /** Explain why this run cannot currently supply an action to execute. */
    private JsonNode noAction(String reason) {
        return result("no_action", "context_id", context == null ? null : context.get("context_id"),
            "reason", reason, "rankings", List.of(), "asm_index", asmIndex, "message", reason.replace('_', ' '));
    }

    /** Ask the active ASM for an action; switch ASMs when its limit is reached or it cannot select. */
    public synchronized JsonNode select() {
        if (cancelled) {
            return noAction("cancelled");
        }
        if (activeExecutionReference != null) {
            return noAction("execution_in_progress");
        }
        if (context == null) {
            return noAction("no_context");
        }
        if (configuration.has("total_action_limit") &&
                configuration.get("total_action_limit").bigIntegerValue().compareTo(BigInteger.valueOf(totalCount)) <= 0) {
            return noAction("action_limit_reached");
        }
        if (context.get("choices").isEmpty()) {
            return noAction("no_available_choices");
        }
        // Repeated requests for the same observation return the same choice.
        if (decision != null) {
            return decision.deepCopy();
        }
        JsonNode entries = configuration.get("asms");
        int start = asmIndex;
        int index = start;
        long count = entryCount;
        if (entries.get(index).get("action_limit").bigIntegerValue().compareTo(BigInteger.valueOf(count)) <= 0) {
            index = (index + 1) % entries.size();
            count = 0;
        }
        ObjectNode fallback = null;
        // Try each configured ASM at most once, so switching cannot continue indefinitely.
        for (int attempt = 0; attempt < entries.size(); attempt++) {
            ObjectNode selected = ActionSelector.forAsm(entries.get(index), stateModelData, context, random).select(entries.get(index));
            if (selected.has("choice_id")) {
                ObjectNode value = result("selected", "context_id", context.get("context_id"),
                    "asm_index", index, "message", text(selected, "reason").replace('_', ' '));
                value.setAll(selected);
                asmIndex = index;
                entryCount = count;
                decision = value;
                return value.deepCopy();
            }
            if (fallback == null) {
                fallback = selected;
            }
            index = (index + 1) % entries.size();
            count = 0;
        }
        ObjectNode value = result("no_action", "context_id", context.get("context_id"), "asm_index", start);
        if (entries.size() > 1) {
            fallback = object("reason", "no_applicable_asm", "rankings", fallback.get("rankings"));
        }
        value.setAll(fallback);
        value.put("message", text(value, "reason").replace('_', ' '));
        return value;
    }

    /** Associate an execution reference with the last selection before the driver operates the GUI. */
    public synchronized JsonNode beginExecution(JsonNode selection, JsonNode execution) {
        require(!cancelled, "cancelled", "Exploration was cancelled.");
        require(activeExecutionReference == null, "execution_in_progress", "Another execution is outstanding.");
        require(context != null && decision != null &&
            context.get("context_id").equals(selection.get("context_id")) &&
            selection.path("asm_data_revision").isIntegralNumber() &&
            selection.get("asm_data_revision").bigIntegerValue().equals(BigInteger.valueOf(revision)),
            "stale_selection", "Select again using the current observation and state-model data.");
        require(decision.get("choice_id").equals(selection.get("choice_id")), "invalid_selection", "Not the last selected choice.");
        List<String> reference = key(execution, "execution");
        require(!executionContexts.containsKey(reference) && !stateModelData.executions.containsKey(reference),
            "execution_reference_reused", "Execution identity has already been used.");
        require(reference.subList(0, 2).equals(key(context.get("observation"), "observation").subList(0, 2)),
            "execution_mismatch", "Execution must belong to the selected run.");
        JsonNode choice = list(context.get("choices")).stream()
            .filter(item -> item.get("choice_id").equals(selection.get("choice_id"))).findFirst().orElseThrow();
        executionContexts.put(reference, new ActionExecutionContext(context, choice));
        activeExecutionReference = reference;
        return result("execution_started", "context_id", context.get("context_id"), "execution", execution);
    }

    /** Hold a checked evidence update until all checks for the calling operation have passed. */
    private record Prepared(StateModelData stateModelData, boolean changed, JsonNode payload) {
    }

    /** Check incoming evidence without changing the run; repeated update IDs must contain the same data. */
    private Prepared prepareUpdate(String updateId, JsonNode sources, JsonNode selectedRuns) {
        require(updateId != null && !updateId.isBlank(), "invalid_evidence", "An update ID is required.");
        JsonNode payload = StateModelData.normalize(sources, selectedRuns);
        require(!updates.containsKey(updateId) || same(updates.get(updateId), payload),
            "conflicting_update", "Update ID has different content.");
        StateModelData candidate = stateModelData.extended(sources, selectedRuns, rules);
        return new Prepared(candidate, !same(candidate.snapshot, stateModelData.snapshot), payload);
    }

    /** Install checked evidence and invalidate old decisions; retire the observation if newer evidence replaces it. */
    private void acceptUpdate(String updateId, Prepared prepared) {
        updates.put(updateId, prepared.payload().deepCopy());
        if (!prepared.changed()) {
            return;
        }
        stateModelData = prepared.stateModelData();
        revision++;
        decision = null;
        if (context != null) {
            List<String> reference = key(context.get("observation"), "observation");
            JsonNode current = stateModelData.observations.get(reference);
            JsonNode sequence = stateModelData.sequences.get(List.of(reference.get(0), text(current, "test_sequence")));
            if (!text(stateModelData.runRecords.get(reference.subList(0, 2)), "status").equals("running") ||
                    !text(sequence, "status").equals("running")) {
                retireContext();
                return;
            }
            for (var entry : stateModelData.observations.entrySet()) {
                JsonNode observed = entry.getValue();
                if (entry.getKey().subList(0, 2).equals(reference.subList(0, 2)) &&
                        observed.get("test_sequence").equals(current.get("test_sequence")) &&
                        observed.get("observation_index").bigIntegerValue().compareTo(
                            current.get("observation_index").bigIntegerValue()) > 0) {
                    retireContext();
                    break;
                }
            }
        }
    }

    /** Update the abstract model from recorded evidence, such as a newly captured state. */
    public synchronized JsonNode updateEvidence(String updateId, JsonNode sources, JsonNode selectedRuns) {
        Prepared prepared = prepareUpdate(updateId, sources, selectedRuns);
        acceptUpdate(updateId, prepared);
        return result("evidence_updated", "changed", prepared.changed());
    }

    /** Match execution evidence to the selected action, update the model, and count that execution only once. */
    public synchronized JsonNode reportExecution(JsonNode execution, String updateId, JsonNode sources, JsonNode selectedRuns) {
        List<String> reference = key(execution, "execution");
        ActionExecutionContext executionContext = executionContexts.get(reference);
        require(executionContext != null && !Boolean.FALSE.equals(executionContext.invoked), "unknown_execution", "Execution was not begun through this ASM.");
        Prepared prepared = prepareUpdate(updateId, sources, selectedRuns);
        JsonNode record = prepared.stateModelData().executions.get(reference);
        require(record != null, "execution_mismatch", "The reported execution is missing.");
        JsonNode actual = record.get("execution");
        require(actual.get("source_observation").equals(executionContext.context.get("observation").get("observation")) &&
            actual.get("action_description").equals(executionContext.choice.get("action_description")) &&
            actual.get("target_widget").equals(executionContext.choice.get("target_widget")),
            "execution_mismatch", "Execution does not belong to the selected action.");
        acceptUpdate(updateId, prepared);
        executionContext.invoked = true;
        // Later reports for this execution may add its result without consuming another action.
        if (counted.add(reference)) {
            entryCount++;
            totalCount++;
        }
        if (!StateModelData.PENDING.contains(text(record, "status")) && reference.equals(activeExecutionReference)) {
            activeExecutionReference = null;
            retireContext();
        }
        return result("execution_updated", "execution", execution, "status", record.get("status"), "changed", prepared.changed());
    }

    /** Clear an action that never started, without increasing execution counts; a fresh observation is required. */
    public synchronized JsonNode reportNotInvoked(JsonNode execution) {
        List<String> reference = key(execution, "execution");
        require(executionContexts.containsKey(reference) && reference.equals(activeExecutionReference), "unknown_execution", "No such outstanding execution.");
        require(!stateModelData.executions.containsKey(reference), "execution_already_recorded", "Execution evidence already exists.");
        executionContexts.get(reference).invoked = false;
        activeExecutionReference = null;
        retireContext();
        return result("execution_not_invoked", "execution", execution);
    }

    /** Run exploration using driver capabilities, with a finite observation-cycle limit. */
    public JsonNode explore(ExplorationDriver driver, int maxCycles) throws Exception {
        return Exploration.explore(this, driver, maxCycles);
    }

    /** Run the observation, selection, and execution cycle with the default observation-cycle limit. */
    public JsonNode explore(ExplorationDriver driver) throws Exception {
        return explore(driver, 1000);
    }

    /** Check cancellation, outstanding execution, and the overall action limit before capturing the GUI again. */
    synchronized JsonNode explorationStop() {
        if (cancelled) {
            return noAction("cancelled");
        }
        if (activeExecutionReference != null) {
            return noAction("execution_in_progress");
        }
        if (configuration.has("total_action_limit") &&
                configuration.get("total_action_limit").bigIntegerValue().compareTo(BigInteger.valueOf(totalCount)) <= 0) {
            return noAction("action_limit_reached");
        }
        return null;
    }

    /** Keep historical trace runs selected when adding evidence from the current exploration. */
    synchronized JsonNode combinedRuns(JsonNode incoming) {
        Map<List<String>, JsonNode> combined = new HashMap<>();
        for (JsonNode reference : stateModelData.snapshot.get("selected_runs")) {
            combined.put(List.of(text(reference, "source"), text(reference, "run")), reference);
        }
        for (JsonNode reference : incoming) {
            combined.put(List.of(text(reference, "source"), text(reference, "run")), reference);
        }
        return array(combined.values());
    }

    /** Prevent further selection; an action already started still needs its execution result reported. */
    public synchronized JsonNode cancel() {
        cancelled = true;
        return result("cancelled");
    }
}
