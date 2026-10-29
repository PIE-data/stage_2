package es.ulpgc.piedata.search.benchmark;

import es.ulpgc.piedata.search.SearchCatalog;
import es.ulpgc.piedata.search.SearchRequest;
import es.ulpgc.piedata.search.SearchService;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SearchBenchmark {

    @Param({"UNSET"})
    public String dataDir;

    @Param({"single", "multi", "filtered"})
    public String workload;

    private SearchService service;
    private SearchRequest request;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path directory = Path.of(dataDir).toAbsolutePath().normalize();

        if (!Files.isRegularFile(
                directory.resolve("datamarts/datamart.db"))) {
            throw new IllegalStateException(
                    "Benchmark datamart not found under " + directory
            );
        }

        SearchCatalog catalog = new SearchCatalog(directory);
        Number books = (Number) catalog.status().get("books_indexed");

        if (books.longValue() != 1000) {
            throw new IllegalStateException(
                    "Expected 1000 books, found " + books
            );
        }

        service = new SearchService(directory);

        request = switch (workload) {
            case "single" -> SearchRequest.parse(
                    "sea", null, null, null, "0", "20"
            );
            case "multi" -> SearchRequest.parse(
                    "sea ship", null, null, null, "0", "20"
            );
            case "filtered" -> SearchRequest.parse(
                    "sea ship", "Stacpoole", "en", "2017", "0", "20"
            );
            default -> throw new IllegalArgumentException(
                    "Unknown workload: " + workload
            );
        };

        var check = service.search(request);

        if (check.total() == 0) {
            throw new IllegalStateException(
                    "Benchmark query unexpectedly matches no books: "
                            + workload
            );
        }

        System.out.println(
                "Validated workload=" + workload
                        + ", books=" + books
                        + ", matches=" + check.total()
        );
    }

    @Benchmark
    public SearchService.SearchResponse search() throws Exception {
        return service.search(request);
    }

    public static void main(String[] args) throws Exception {
        String configuredDir = System.getenv("BENCH_DATA_DIR");

        if (configuredDir == null || configuredDir.isBlank()) {
            throw new IllegalArgumentException(
                    "Set BENCH_DATA_DIR to the tier-1000 workspace"
            );
        }

        Path directory = Path.of(configuredDir)
                .toAbsolutePath()
                .normalize();

        Path root = findProjectRoot();
        Path outputDir = root.resolve("results/jmh");
        Files.createDirectories(outputDir);

        String timestamp = DateTimeFormatter
                .ofPattern("uuuuMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Path resultFile = outputDir.resolve(
                "search-tier1000-" + timestamp + ".csv"
        );

        var options = new OptionsBuilder()
                .include("^" + SearchBenchmark.class.getName()
                        .replace(".", "\\.") + "\\.search$")
                .param("dataDir", directory.toString())
                .warmupIterations(5)
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(10)
                .measurementTime(TimeValue.seconds(2))
                .forks(1)
                .threads(1)
                .jvmArgs("-Xms4G", "-Xmx4G")
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.CSV)
                .result(resultFile.toString())
                .build();

        new Runner(options).run();

        System.out.println("Results saved to: " + resultFile);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(
                    current.resolve("search-service"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException(
                "Cannot locate the stage_2 project root"
        );
    }
}