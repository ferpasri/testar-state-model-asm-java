package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.asm.Json.*;

final class ExplorationControlTest {
    /**
     * Simulate GUI operations using the execution-lifecycle case instead of a browser.
     * observe returns its initial form and actions; execute supplies its pending or
     * completed evidence. The call list shows what the exploration loop requested.
     */
    private static final class RecordedExplorationDriver implements ExplorationDriver {
        final JsonNode data;
        final List<String> calls = new ArrayList<>();
        boolean pending;
        boolean unexpectedError;

        RecordedExplorationDriver(JsonNode data) {
            this.data = data;
        }

        @Override
        public ObservationUpdate observe() {
            calls.add("observe and discover");
            JsonNode snapshot = data.get("evidence_sets").get("initial");
            return new ObservationUpdate(data.get("steps").get(0).get("context"),
                snapshot.get("sources"), snapshot.get("selected_runs"));
        }

        @Override
        public JsonNode prepareExecution(JsonNode selection) {
            calls.add("check applicability");
            return data.get("steps").get(2).get("execution");
        }

        @Override
        public ExecutionUpdate execute(JsonNode selection, JsonNode execution) {
            calls.add("execute and record");
            if (unexpectedError) {
                throw new IllegalStateException("The driver connection was lost.");
            }
            JsonNode snapshot = data.get("evidence_sets").get(pending ? "pending" : "completed");
            return new ExecutionUpdate(snapshot.get("sources"), snapshot.get("selected_runs"));
        }
    }

    @Test
    void libraryExecutesOneActionUpdatesStateModelDataAndStopsBeforeAnotherCapture() throws Exception {
        // Start with a form and two available clicks, then supply evidence that the selected click completed.
        // With an overall limit of one action, exploration must update the model and stop before capturing again.
        JsonNode data = Examples.input("execution-lifecycle");
        ((ObjectNode) data.get("configuration")).put("total_action_limit", 1);
        AsmRun run = Examples.start(data);
        RecordedExplorationDriver driver = new RecordedExplorationDriver(data);

        JsonNode stopped = run.explore(driver);

        assertEquals("action_limit_reached", stopped.get("reason").asText());
        assertEquals(List.of("observe and discover", "check applicability", "execute and record"), driver.calls);
        JsonNode action = list(run.model().get("actionEvidence").get("actions")).stream()
            .filter(item -> !item.get("executions").isEmpty()).findFirst().orElseThrow();
        assertEquals("completed", action.get("executions").get(0).get("status").asText());
    }

    @Test
    void pendingExecutionStopsTheLoopUntilItsResultIsReported() throws Exception {
        // The driver reports that the selected action is still awaiting a result.
        // The loop must stop here rather than choose another action against an uncertain GUI state.
        JsonNode data = Examples.input("execution-lifecycle");
        AsmRun run = Examples.start(data);
        RecordedExplorationDriver driver = new RecordedExplorationDriver(data);
        driver.pending = true;
        assertEquals("execution_in_progress", run.explore(driver).get("reason").asText());
    }

    @Test
    void unexpectedIntegrationFailureDoesNotInventAnExecutionResult() throws Exception {
        // Simulate a lost driver connection after execution was begun.
        // Keep the execution outstanding for investigation; do not invent success or failure evidence.
        JsonNode data = Examples.input("execution-lifecycle");
        AsmRun run = Examples.start(data);
        RecordedExplorationDriver driver = new RecordedExplorationDriver(data);
        driver.unexpectedError = true;
        assertThrows(IllegalStateException.class, () -> run.explore(driver));
        assertEquals("execution_in_progress", run.select().get("reason").asText());
        assertTrue(list(run.model().get("actionEvidence").get("actions")).stream()
            .allMatch(action -> action.get("executions").isEmpty()));
    }

    @Test
    void controllerSwitchesAfterOneActionAndReturnsAfterTheNext() throws Exception {
        // The shared case configures Unvisited at index 0 and Random at index 1, each limited to one action.
        // Run its context, selection, and execution-report operations; selections must follow 0 -> 1 -> 0.
        JsonNode data = Examples.input("controller-action-limits-and-return");
        JsonNode expected = Examples.expected("controller-action-limits-and-return");
        List<Integer> indexes = list(expected.get("responses")).stream()
            .filter(item -> text(item, "result_type").equals("selected"))
            .map(item -> item.get("asm_index").asInt()).toList();
        assertEquals(List.of(0, 1, 0), indexes);
        SharedConformanceTest.verify(data, expected);
    }
}
