package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.EvidenceJson;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.asm.Json.*;

/** Growing GUI histories must keep the same meaning when accepted records are reused. */
final class EvidenceUpdatesTest {
    @Test
    void acceptedStatesDoNotHideExcessiveNestingInNewRecords() throws Exception {
        JsonNode data = Examples.input("execution-lifecycle");
        JsonNode completed = data.get("evidence_sets").get("completed");
        ArrayNode observations = (ArrayNode) completed.get("sources").get(0).get("evidence").get("observations");
        ObjectNode properties = (ObjectNode) observations.get(observations.size() - 1)
            .get("recorded_state").get("properties");
        ObjectNode nested = properties;
        for (int index = 0; index < 60; index++) {
            nested = nested.putObject("nested");
        }
        AsmRun run = Examples.start(data);
        JsonNode before = run.model();
        assertThrows(AsmException.class, () ->
            run.updateEvidence("deep", completed.get("sources"), completed.get("selected_runs")));
        assertEquals(before, run.model());
    }

    @Test
    void addingAnExecutionReusesTheAcceptedStateAndMatchesAFullRebuild() throws Exception {
        JsonNode data = Examples.input("execution-lifecycle");
        JsonNode initial = data.get("evidence_sets").get("initial");
        JsonNode completed = data.get("evidence_sets").get("completed");
        JsonNode rules = data.get("configuration").get("abstraction");
        StateModelData before = new StateModelData(initial.get("sources"), initial.get("selected_runs"), rules);
        StateModelData after = before.extended(new PreparedSnapshot(completed.get("sources"),
            completed.get("selected_runs"), before), rules);

        // The original GUI state is unchanged; only the execution and its resulting state are new.
        for (var entry : before.observations.entrySet()) {
            assertSame(entry.getValue(), after.observations.get(entry.getKey()));
        }
        StateModelData rebuilt = new StateModelData(completed.get("sources"), completed.get("selected_runs"), rules);
        assertTrue(same(rebuilt.model, after.model));
        assertSame(after, after.extended(new PreparedSnapshot(completed.get("sources"),
            completed.get("selected_runs"), after), rules));
    }

    @Test
    void invalidNewStateDoesNotReplaceAcceptedDataOrConsumeTheUpdateId() throws Exception {
        JsonNode data = Examples.input("execution-lifecycle");
        JsonNode completed = data.get("evidence_sets").get("completed");
        JsonNode invalid = completed.deepCopy();
        ArrayNode observations = (ArrayNode) invalid.get("sources").get(0).get("evidence").get("observations");
        ObjectNode widget = (ObjectNode) observations.get(observations.size() - 1)
            .get("recorded_state").get("widgets").get(0);
        widget.put("parent", "missing-widget");
        AsmRun run = Examples.start(data);
        JsonNode before = run.model();

        // A malformed newly captured widget must not damage the previously accepted model.
        assertThrows(AsmException.class, () -> run.updateEvidence("next", invalid.get("sources"), invalid.get("selected_runs")));
        assertEquals(before, run.model());
        assertEquals(0, run.asmDataRevision());
        assertTrue(run.updateEvidence("next", completed.get("sources"), completed.get("selected_runs")).get("changed").asBoolean());
    }

    @Test
    void insertingAnEarlierObservationStillReceivesFullLifecycleValidation() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        ArrayNode observations = (ArrayNode) snapshot.get("sources").get(0).get("evidence").get("observations");
        ObjectNode later = observations.get(0).deepCopy();
        later.put("id", "later");
        later.put("observation_index", 3);
        observations.add(later);
        AsmRun run = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"));
        ObjectNode middle = observations.get(0).deepCopy();
        middle.put("id", "middle");
        middle.put("observation_index", 2);
        observations.add(middle);

        // Importing historical evidence can insert a state before the latest accepted state.
        assertTrue(run.updateEvidence("insert", snapshot.get("sources"), snapshot.get("selected_runs")).get("changed").asBoolean());
        AsmRun rebuilt = new AsmRun(data.get("configuration"), snapshot.get("sources"), snapshot.get("selected_runs"));
        assertTrue(same(rebuilt.model(), run.model()));
    }

    @Test
    void changingOnlyAnExecutionSummaryCannotBypassLifecycleChecks() throws Exception {
        JsonNode data = Examples.input("execution-lifecycle");
        JsonNode pending = data.get("evidence_sets").get("pending");
        AsmRun run = new AsmRun(data.get("configuration"), pending.get("sources"), pending.get("selected_runs"));
        JsonNode before = run.model();
        ObjectNode execution = (ObjectNode) pending.get("sources").get(0).get("evidence").get("executions").get(0);
        execution.put("status", "completed");
        assertThrows(AsmException.class, () -> run.updateEvidence("invalid-status", pending.get("sources"), pending.get("selected_runs")));
        assertEquals(before, run.model());
    }

    @Test
    void retriesCompareValuesAndRetainAnIndependentCopyOfCallerData() throws Exception {
        JsonNode data = Examples.input("empty-start");
        JsonNode snapshot = data.get("evidence_sets").get("observed");
        ObjectNode properties = (ObjectNode) snapshot.get("sources").get(0).get("evidence").get("observations")
            .get(0).get("recorded_state").get("properties");
        properties.put("example.number", 1);
        AsmRun run = new AsmRun(data.get("configuration"), array(List.of()), array(List.of()));
        run.updateEvidence("capture", snapshot.get("sources"), snapshot.get("selected_runs"));
        JsonNode before = run.model();
        properties.put("example.number", new BigDecimal("1.00"));
        assertFalse(run.updateEvidence("capture", snapshot.get("sources"), snapshot.get("selected_runs")).get("changed").asBoolean());
        properties.put("example.number", true);
        AsmException error = assertThrows(AsmException.class, () ->
            run.updateEvidence("capture", snapshot.get("sources"), snapshot.get("selected_runs")));
        assertEquals("conflicting_update", error.code());
        assertEquals(before, run.model());
    }

    @Test
    void contentHashesPreserveSourceIdentityAndNumericComparison() throws Exception {
        JsonNode value = EvidenceJson.parse("{\"name\":\"\u00e9quipe \ud83d\ude00\",\"number\":1.25,\"large\":18446744073709551615,\"null\":null}");
        String previousHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(previousHash, ContentHash.source(value));
        assertEquals(ContentHash.canonical(EvidenceJson.parse("{\"a\":1,\"b\":null}")),
            ContentHash.canonical(EvidenceJson.parse("{\"b\":null,\"a\":1.00}")));
        assertNotEquals(ContentHash.canonical(EvidenceJson.parse("1")), ContentHash.canonical(EvidenceJson.parse("true")));
    }

    @Test
    void benchmarkProducesCompletedTracesAndRepeatableModels(@TempDir Path temporary) throws Exception {
        // Two clicks connect three captured GUI states. Timings vary; recorded facts must not.
        var first = PerformanceBenchmark.sample(temporary.resolve("first"), 2, 3, 2);
        var second = PerformanceBenchmark.sample(temporary.resolve("second"), 2, 3, 2);
        assertEquals(3, first.get("observations"));
        assertEquals(2, first.get("executions"));
        assertEquals(first.get("model_hash"), second.get("model_hash"));
    }
}
