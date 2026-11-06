package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class UnvisitedActionSelectionTest {
    @Test
    void tryANewActionHereBeforeNavigatingElsewhere() throws Exception {
        // This shared case starts at state A with three eligible actions: to-b, to-c, and new-a.
        // Recorded executions already connect A to B and C. Those states have unvisited actions,
        // but new-a has never been executed in A. The configured ASM is Unvisited.
        // The helper builds the model from these records and asks for one action; it returns
        // a JSON decision, not an execution result.
        JsonNode selected = Examples.select("local-unvisited-before-navigation");

        // The decision must identify new-a: try an unvisited action here before navigating away.
        // The reason confirms that this is local selection rather than a navigation step.
        assertEquals("new-a", selected.get("choice_id").asText());
        assertEquals("local_unvisited_action", selected.get("reason").asText());
    }

    @Test
    void nearerStateWinsEvenWhenAFartherStateHasMoreUnvisitedActions() throws Exception {
        // This shared case starts at A, where all current actions have already been tried.
        // The trace contains A -> B and A -> C -> D. B has one unvisited action; D has three.
        // The helper builds that model and asks the Unvisited ASM for its next action.
        JsonNode selected = Examples.select("navigation-nearest-state");

        // Choose to-b, the first step toward B. Distance 1 counts the recorded transition
        // needed to reach B; unvisited_actions 1 describes the opportunity waiting there.
        assertEquals("to-b", selected.get("choice_id").asText());
        assertEquals(1, selected.get("navigation").get("distance").asInt());
        assertEquals(1, selected.get("navigation").get("unvisited_actions").asInt());
    }

    @Test
    void equallyNearStatesPreferMoreDistinctUnvisitedActions() throws Exception {
        // This shared case starts at A with no local unvisited action.
        // Recorded transitions reach B and C in one step each. C has two distinct unvisited
        // abstract actions, more than B. The helper asks the Unvisited ASM to choose a route.
        JsonNode selected = Examples.select("navigation-most-unvisited-actions");

        // Choose to-c: when destinations are equally near, prefer the one with more
        // unvisited abstract actions. The returned count describes C, not actions at A.
        assertEquals("to-c", selected.get("choice_id").asText());
        assertEquals(2, selected.get("navigation").get("unvisited_actions").asInt());
    }
}
