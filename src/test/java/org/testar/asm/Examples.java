package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import org.testar.evidence.EvidenceJson;

/**
 * Load the shared JSON scenarios downloaded from ASM Specs. Each scenario contains
 * recorded states, available actions, execution history, abstraction rules, and API
 * operations. These tests use that data without launching a browser.
 */
final class Examples {
    private Examples() {
    }

    /** Load the scenario setup and operations; the name identifies a shared case directory. */
    static JsonNode input(String name) throws Exception {
        return read(name, "input.json");
    }

    /** Load the expected API responses used to verify the scenario's operations. */
    static JsonNode expected(String name) throws Exception {
        return read(name, "expected.json");
    }

    static JsonNode read(String name, String file) throws Exception {
        return EvidenceJson.parse(Files.readString(PrepareConformance.prepared().resolve("cases").resolve(name).resolve(file)));
    }

    /**
     * Build an ASM run from the scenario's configuration and initial trace data.
     * If several choices tie, choose index zero so the example gives a repeatable result.
     */
    static AsmRun start(JsonNode data) {
        JsonNode initial = data.get("evidence_sets").get(data.path("initial_evidence").asText());
        return new AsmRun(data.get("configuration"), initial.get("sources"), initial.get("selected_runs"), size -> 0);
    }

    /**
     * Load a shared scenario, build its abstract model, and supply its first current
     * observation and eligible actions to the ASM. Return one selection response:
     * choice_id identifies the action to execute, reason explains why it was chosen,
     * rankings describes matching execution counts, and navigation describes a route
     * when needed. Selecting does not execute the action or change the application.
     */
    static JsonNode select(String name) throws Exception {
        JsonNode data = input(name);
        AsmRun run = start(data);
        run.setContext(data.get("steps").get(0).get("context"));
        return run.select();
    }
}
