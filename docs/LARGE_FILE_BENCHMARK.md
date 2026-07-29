# Large-File Baseline Benchmark

`LargeFileBenchmark` measures Shed's current `FileBuffer` open, one visible-document edit, and atomic save path. Every iteration copies the input to a temporary file before editing, so the supplied file is read only. The benchmark is local-only and does not send telemetry.

Build the shaded JAR, then run the 1 MiB, 100 MiB, and 1 GiB UTF-8 inputs with the same heap limit and iteration count:

```sh
mvn -B -q -DskipTests package
java -Xmx4g -cp target/shed-2.0.0.jar shed.LargeFileBenchmark --iterations 3 fixtures/large-1m.txt > benchmark-1m.txt
java -Xmx4g -cp target/shed-2.0.0.jar shed.LargeFileBenchmark --iterations 3 fixtures/large-100m.txt > benchmark-100m.txt
java -Xmx4g -cp target/shed-2.0.0.jar shed.LargeFileBenchmark --iterations 3 fixtures/large-1g.txt > benchmark-1g.txt
```

Fixture generation is tracked separately. Use locally generated or otherwise trusted UTF-8 inputs until those fixtures land. Do not compare runs with different heap limits, JDKs, storage media, or input bytes.

Each key/value report records Java and OS environment, input path and bytes, workload, a reproducible command, successful sample count, median and nearest-rank p95 latency, median and p95 observed heap delta, and every failure. `-1` timing or heap fields mean an operation had no successful samples. A recorded failure is baseline evidence, not a successful result.
