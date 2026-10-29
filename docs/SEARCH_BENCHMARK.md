# Search Service benchmark — tier 1000

## Scope

This JMH benchmark measures calls to `SearchService.search(request)` against
a Stage 2 datamart containing 1,000 books.

The measured operation includes the work performed inside the search method,
including database access, matching, ranking and pagination. Request parsing
is performed during setup and is excluded from the measurement.

HTTP handling, JSON serialization, the gateway and network latency are excluded.
These results are not end-to-end HTTP latency measurements.

## Dataset

The datamart was built by the Stage 2 Indexing Service from the tier-1000
datalake and ingestion receipts.

- Books: 1,000
- Terms: 366,187
- Postings: 5,876,955
- Reported index size: 926.9 MB

Before measuring each workload, setup checks that the database exists,
contains exactly 1,000 books and returns at least one matching book.

The dataset and generated database are stored outside the Git repository.

## Environment

- CPU: Intel Core i7-10510U, 4 cores / 8 logical processors
- Total physical RAM reported by Windows: 15.8 GiB
- Operating system: Windows
- Java: Eclipse Temurin 17.0.17
- JMH: 1.37
- Benchmark JVM heap: `-Xms4G -Xmx4G`

## Configuration

- Mode: AverageTime
- Unit: milliseconds per operation
- Worker threads: 1
- Forks: 1 per workload
- Warm-up: 5 iterations of 2 seconds
- Measurement: 10 iterations of 2 seconds
- Pagination: offset 0, limit 20

The same query is repeated during warm-up and measurement. The benchmark
does not reset operating-system filesystem caches between operations and
does not represent cold-start performance.

## Workloads

| Name | Query | Author | Language | Release year |
|---|---|---|---|---|
| single | sea | — | — | — |
| multi | sea ship | — | — | — |
| filtered | sea ship | Stacpoole | en | 2017 |

Multiple query terms use AND semantics. The year filter refers to the
Gutenberg e-book release year.

## Results

Source: `results/jmh/search-tier1000-20261029-171142-881.csv`

| Workload | Mean (ms/op) | JMH error margin, 99.9% (ms/op) |
|---|---:|---:|
| single | 4.428038 | 0.719400 |
| multi | 5.260305 | 0.628057 |
| filtered | 3.404943 | 0.281035 |

Lower values indicate less time per search.

The filtered workload has the lowest measured mean in this run.
These workloads differ in both query terms and selectivity, so the results
do not isolate the cost of any single filter or processing stage.

The results describe one machine and one JVM fork per workload.
They do not establish performance under concurrent HTTP load or across replicas.

## Reproduction with IntelliJ IDEA

1. Prepare a Stage 2 tier-1000 workspace containing
   `datamarts/datamart.db`.
2. Open the root Maven project using JDK 17.
3. In the Maven tool window, run the root project's `clean` lifecycle phase,
   followed by `verify`. This generates the JMH benchmark metadata.
4. Open:
   `search-service/src/test/java/es/ulpgc/piedata/search/benchmark/SearchBenchmark.java`.
5. Create or select an Application run configuration for its `main` method,
   using the search-service test classpath.
6. Set the working directory to the repository root.
7. Add the environment variable `BENCH_DATA_DIR`, with the absolute path to
   the tier-1000 workspace. Use the workspace directory, not the database file.
8. Stop other project services and avoid heavy background workloads.
9. Run `SearchBenchmark`.

The runner writes a timestamped CSV under `results/jmh`.
The JVM heap and JMH iteration settings are configured in the Java runner.

If `/META-INF/BenchmarkList` is missing, rebuild using Maven and check that
`search-service/target/test-classes/META-INF/BenchmarkList` exists.