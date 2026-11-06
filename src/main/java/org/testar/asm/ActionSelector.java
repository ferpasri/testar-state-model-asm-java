package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import static org.testar.asm.Json.*;

/**
 * Common state/action matching used by ASMs. A current button can share execution
 * history with a recorded button when the configured abstraction matches them.
 * Each concrete selector implements its own rule for choosing the next action.
 */
abstract class ActionSelector {
    protected final StateModelData stateModelData;
    protected final JsonNode context;
    protected final IntUnaryOperator random;
    protected final List<JsonNode> actions;
    protected final Map<List<String>, String> nodes = new HashMap<>();
    protected final Map<String, JsonNode> representatives = new HashMap<>();
    protected final Map<String, String> choiceActions = new HashMap<>();

    ActionSelector(StateModelData stateModelData, JsonNode context, IntUnaryOperator random) {
        this.stateModelData = stateModelData;
        this.context = context;
        this.random = random;
        actions = list(stateModelData.model.get("actionEvidence").get("actions"));
        for (JsonNode node : stateModelData.model.get("nodes")) {
            JsonNode representative = list(node.get("members")).stream()
                .min(Comparator.comparing(item -> key(item, "observation"), KEYS)).orElseThrow();
            if (node.hasNonNull("groupId")) {
                for (JsonNode member : node.get("members")) {
                    nodes.put(key(member, "observation"), text(node, "id"));
                }
                representatives.put(text(node, "id"), representative);
            }
        }
    }

    /** Choose equally among suitable alternatives; skip a random draw when only one remains. */
    protected <T> T choose(List<T> items) {
        require(!items.isEmpty(), "processing_limit", "No selection alternatives.");
        int index = items.size() == 1 ? 0 : random.applyAsInt(items.size());
        require(index >= 0 && index < items.size(), "invalid_configuration", "Invalid random index.");
        return items.get(index);
    }

    /**
     * Explain each current action using matching executions from the traces.
     * Unknown matches can be retained, or counted only at this exact observation.
     */
    protected List<JsonNode> rankings(boolean includeUnmatchedChoices, boolean useObservationLocalCounts) {
        var rankings = new ArrayList<JsonNode>();
        List<JsonNode> choices = list(context.get("choices"));
        choices.sort(Comparator.comparing(item -> text(item, "choice_id"), TEXT));
        for (JsonNode choice : choices) {
            JsonNode reference = context.get("observation");
            List<String> observationKey = key(reference, "observation");
            var candidates = new ArrayList<JsonNode>();
            for (JsonNode action : actions) {
                boolean matches = list(action.get("availability")).stream().anyMatch(available -> {
                    JsonNode binding = available.get("reference");
                    return key(binding, "observation").equals(observationKey) &&
                        binding.get("actionDescription").equals(choice.get("action_description")) &&
                        binding.get("targetWidget").equals(choice.get("target_widget"));
                });
                if (matches) {
                    candidates.add(action);
                }
            }
            JsonNode action = candidates.size() == 1 ? candidates.get(0) : null;
            String match = !nodes.containsKey(observationKey) ? "unmatched_state" :
                action == null ? "unmatched_action" : action.path("ambiguous").asBoolean() ? "ambiguous_action" : "matched";
            String scope = null;
            var references = new ArrayList<JsonNode>();
            if (match.equals("matched")) {
                choiceActions.put(text(choice, "choice_id"), text(action, "id"));
                scope = "abstract_action";
                action.get("executions").forEach(record -> references.add(record.get("reference")));
            } else if (useObservationLocalCounts) {
                scope = "recorded_binding";
                for (var entry : stateModelData.executions.entrySet()) {
                    JsonNode execution = entry.getValue().get("execution");
                    if (entry.getKey().subList(0, 2).equals(observationKey.subList(0, 2)) &&
                            execution.get("source_observation").equals(reference.get("observation")) &&
                            execution.get("action_description").equals(choice.get("action_description")) &&
                            execution.get("target_widget").equals(choice.get("target_widget"))) {
                        references.add(object("source", entry.getKey().get(0), "run", entry.getKey().get(1),
                            "execution", entry.getKey().get(2)));
                    }
                }
            }
            references.sort(Comparator.comparing(item -> key(item, "execution"), KEYS));
            Map<String, Integer> counts = null;
            if (scope != null) {
                counts = new LinkedHashMap<>();
                for (String status : StateModelData.STATUSES) {
                    counts.put(status, 0);
                }
                for (JsonNode item : references) {
                    String status = text(stateModelData.executions.get(key(item, "execution")), "status");
                    counts.put(status, counts.get(status) + 1);
                }
            }
            rankings.add(object("choice_id", choice.get("choice_id"), "match", match,
                "eligible", scope != null || includeUnmatchedChoices, "count_scope", scope,
                "recorded_executions", scope == null ? null : references.size(),
                "status_counts", counts, "supporting_executions", references));
        }
        return rankings;
    }

    /** Create the selector named by the run configuration. */
    static ActionSelector forAsm(JsonNode asm, StateModelData data, JsonNode context, IntUnaryOperator random) {
        return switch (text(asm, "name")) {
            case "random" -> new RandomActionSelector(data, context, random);
            case "unvisited" -> new UnvisitedActionSelector(data, context, random);
            default -> throw new AsmException("invalid_configuration", "Unknown ASM.");
        };
    }

    abstract ObjectNode select(JsonNode asm);
}
