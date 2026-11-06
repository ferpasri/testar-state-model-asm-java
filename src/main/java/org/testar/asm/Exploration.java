package org.testar.asm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import static org.testar.asm.Json.*;

/**
 * Runs the observe -> select -> execute -> update cycle. Selection belongs to the
 * ASM; capturing the GUI and performing the action belong to the exploration driver.
 */
final class Exploration {
    private Exploration() {
    }

    static JsonNode explore(AsmRun run, ExplorationDriver driver, int maxCycles) throws Exception {
        require(maxCycles > 0, "invalid_configuration", "maxCycles must be positive.");
        String prefix = UUID.randomUUID().toString();
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            JsonNode stopped = run.explorationStop();
            if (stopped != null) {
                return stopped;
            }
            var observation = driver.observe();
            run.updateEvidence(prefix + ":observe:" + cycle, observation.sources(), run.combinedRuns(observation.selectedRuns()));
            run.setContext(observation.context());
            JsonNode selection = run.select();
            if (text(selection, "result_type").equals("no_action")) {
                return selection;
            }
            JsonNode execution = driver.prepareExecution(selection);
            if (execution == null) {
                continue;
            }
            run.beginExecution(selection, execution);
            // An unexpected driver exception retains the outstanding execution.
            var report = driver.execute(selection, execution);
            if (!report.invoked()) {
                run.reportNotInvoked(execution);
                continue;
            }
            JsonNode result = run.reportExecution(execution, prefix + ":execute:" + cycle,
                report.sources(), run.combinedRuns(report.selectedRuns()));
            if (StateModelData.PENDING.contains(text(result, "status"))) {
                return run.select();
            }
        }
        JsonNode stopped = run.explorationStop();
        if (stopped != null) {
            return stopped;
        }
        return object("result_type", "exploration_stopped", "reason", "cycle_limit_reached",
            "message", "The exploration cycle limit was reached.");
    }
}
