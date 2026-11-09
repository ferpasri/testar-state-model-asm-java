# Java evidence and ASM benchmark

Run from the ASM Java repository after making its evidence and abstraction dependencies available to Maven:

```powershell
mvn test-compile exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=org.testar.asm.PerformanceBenchmark" "-Dexec.args=--actions 20 --widgets 100 --properties 10 --warmups 2 --repeats 3 --label baseline"
```

After changing the libraries, repeat with `--label optimized --compare PATH/TO/BASELINE/results.json`. Increase `--actions` or `--widgets` to measure growing traces. All options are optional. `--properties` adds properties per widget, alongside its name. `--output` selects the results parent directory, which defaults to `target/benchmarks`.

The fixture has one available click per state and five page titles. Each click produces a new observation and a completed transition. Every observation contains the requested number of widgets. This measures recording and model updates rather than browser automation or selection across many alternative routes. Screenshots are not included.

Workloads are timed independently: record construction, event encoding, in-memory validation/application, synchronized trace writing, trace reading, source preparation, changed ASM updates, unchanged ASM updates, and context plus selection. Write/read workloads include their own serialization and validation; do not add the rows as if they were exclusive phases of one execution. One changed and one unchanged update are supplied for each of the initial and resulting observations.

Warm-ups execute the same work and are excluded from medians. Results include settings, individual samples, model hashes, and Java/OS versions. Each sample saves a trace and its abstract model. Trace counts, execution completion, repeated-update behavior, and repeatable models are checked. Comparisons check matching action, widget, and property counts and identical model hashes; use the same Java version and machine, and compare repeated measurements rather than individual timings.
