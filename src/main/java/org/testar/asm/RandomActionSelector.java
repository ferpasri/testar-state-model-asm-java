package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.IntUnaryOperator;
import static org.testar.asm.Json.*;

/**
 * Give every currently eligible action the same chance of selection.
 * Previous executions and missing abstraction matches do not change that chance.
 */
final class RandomActionSelector extends ActionSelector {
    RandomActionSelector(StateModelData data, JsonNode context, IntUnaryOperator random) {
        super(data, context, random);
    }

    @Override
    ObjectNode select(JsonNode asm) {
        List<JsonNode> rankings = rankings(true, false);
        List<JsonNode> eligible = rankings.stream().filter(item -> item.path("eligible").asBoolean()).toList();
        return object("choice_id", choose(eligible).get("choice_id"), "reason", "random_action", "rankings", rankings);
    }
}
