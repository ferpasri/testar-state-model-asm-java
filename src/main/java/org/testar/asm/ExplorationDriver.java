package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Connects the ASM to a GUI testing technology or tool. The driver captures states,
 * discovers and filters available actions, executes selections, and records evidence.
 * It supplies these capabilities while the ASM controls which action to try next.
 */
public interface ExplorationDriver {
    /** The current observation, its eligible actions, and the recorded evidence supporting them. */

    record ObservationUpdate(JsonNode context, JsonNode sources, JsonNode selectedRuns) {
    }

    /** Updated evidence after an execution attempt; invoked says whether the action was started. */

    record ExecutionUpdate(JsonNode sources, JsonNode selectedRuns, boolean invoked) {
        public ExecutionUpdate(JsonNode sources, JsonNode selectedRuns) {
            this(sources, selectedRuns, true);
        }
    }

    /** Capture the GUI and discover which available actions may currently be selected. */
    ObservationUpdate observe() throws Exception;

    /** Check applicability and allocate an execution reference; return null to refresh. */
    JsonNode prepareExecution(JsonNode selection) throws Exception;

    /** Execute and record the result, including operation errors. */
    ExecutionUpdate execute(JsonNode selection, JsonNode execution) throws Exception;
}
