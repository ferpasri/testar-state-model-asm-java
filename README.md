# TESTAR ASM Java

Native exploration and action selection using concrete execution evidence and abstraction rules.

The library implements Unvisited ASM, Random ASM, directed navigation, and action-count-based switching. It controls the exploration cycle through an `ExplorationDriver` that observes the application, discovers eligible actions, executes selections, and records evidence.

~~~java
import org.testar.asm.AsmRun;
import com.fasterxml.jackson.databind.JsonNode;

AsmRun run = new AsmRun(configuration, historicalSources, selectedRuns);
JsonNode stopped = run.explore(driver);
~~~

Historical source and run lists can be empty. `EvidenceSources.read(directory, sourceId)` loads a trace through the evidence reader; `EvidenceSources.fromEvidence(evidence, sourceId)` projects an existing `ReconstructedEvidence` for selection. Configuration follows the [shared contract](https://github.com/ferpasri/testar-state-model-asm-specs/blob/main/docs/contract.md).

## Build and test

Java 17 and Maven are required. Make the evidence and abstraction artifacts declared in [pom.xml](pom.xml) available through your Maven repository or locally installed artifacts. From this repository:

~~~powershell
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.PrepareConformance"
mvn verify
~~~

The preparation command downloads the release pinned in [compatibility.json](compatibility.json), verifies its archive digest, and stores the shared cases in an ignored cache. Maven builds the JAR in `target/`. The library coordinates are `org.testar:testar-state-model-asm`.

## Readable algorithm tests

- [State and action matching](src/test/java/org/testar/asm/StateAndActionMatchingTest.java): property normalization and action correspondence across traces.
- [Unvisited selection](src/test/java/org/testar/asm/UnvisitedActionSelectionTest.java): local actions, shortest navigation, and target preference.
- [Exploration control](src/test/java/org/testar/asm/ExplorationControlTest.java): driver calls, new evidence, switching, and stopping.

The [shared conformance runner](src/test/java/org/testar/asm/SharedConformanceTest.java) verifies every published scenario in normal and reversed collection order. [Specifications](docs/SPEC.md) describe the implementation responsibilities and public interfaces; remaining work is in [TODO](TODO.md).
