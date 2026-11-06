package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.asm.Json.*;

final class StateAndActionMatchingTest {
    @Test
    void formAndUppercaseFormBecomeOneAbstractState() throws Exception {
        // The shared case supplies current and historical observations of a form page.
        // Its abstraction lowercases app.page, so "form" and "FORM" can match.
        JsonNode data = Examples.input("configured-abstraction");
        AsmRun run = Examples.start(data);
        JsonNode context = data.get("steps").get(0).get("context");
        // Current "form" and historical "FORM" match under the lowercase rule.
        JsonNode sources = data.get("evidence_sets").get("initial").get("sources");
        assertEquals("form", sources.get(0).get("evidence").get("observations").get(0)
            .get("recorded_state").get("properties").get("app.page").asText());
        assertEquals("FORM", sources.get(1).get("evidence").get("observations").get(0)
            .get("recorded_state").get("properties").get("app.page").asText());
        JsonNode node = list(run.model().get("nodes")).stream()
            .filter(item -> list(item.get("members")).contains(context.get("observation"))).findFirst().orElseThrow();
        // The model groups three recorded observations; their original values remain unchanged.
        assertEquals(3, node.get("members").size());
    }

    @Test
    void widgetNamesLinkActionsDespiteDifferentRecordedWidgetIds() throws Exception {
        // The current form offers clicks on widgets First and Second. A historical trace
        // already clicked First, using a different widget ID. Action abstraction matches
        // widget names, so that execution counts against the current First action.
        // The helper returns the Unvisited ASM decision and its per-choice explanations.
        JsonNode selected = Examples.select("configured-abstraction");
        JsonNode first = selected.get("rankings").get(0);
        JsonNode second = selected.get("rankings").get(1);
        // "one" and "old:one" both describe First, whose historical click counts.
        assertEquals("choice-1", first.get("choice_id").asText());
        assertEquals(1, first.get("recorded_executions").asInt());
        assertEquals(0, second.get("recorded_executions").asInt());
        // Second has no matching execution, so the ASM selects its current choice ID.
        assertEquals("choice-2", selected.get("choice_id").asText());
    }
}
