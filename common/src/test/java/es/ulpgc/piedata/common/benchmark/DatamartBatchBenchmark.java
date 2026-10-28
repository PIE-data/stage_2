package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.datamart.GoldenBooks;
import es.ulpgc.piedata.common.datamart.IndexedBook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Transaction batching: the 20 golden books written as new books, either one transaction per book
 * ({@code oneTransactionPerBook}) or all in one transaction ({@code oneBatch}). Scores are per book
 * ({@code @OperationsPerInvocation(20)}), so they compare directly. The datamart is filled by
 * {@link BenchData#preload()} first; the 20 books are removed before every call (not measured).
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=DatamartBatchBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatamartBatchBenchmark {

    private static final int BOOKS = 20;

    private Path dir;
    private Datamart datamart;
    private List<IndexedBook> golden;

    @Setup(Level.Trial)
    public void open() throws IOException, SQLException {
        dir = Files.createTempDirectory("datamart-batch-bench");
        datamart = Datamart.open(dir.resolve("datamart.db"));
        long start = System.nanoTime();
        int books = datamart.rebuild(BenchData.preload());
        System.out.printf("%n[setup] datamart filled with %d books in %.1f s (cache %d MiB)%n",
                books, (System.nanoTime() - start) / 1e9, Datamart.DEFAULT_CACHE_MB);
        golden = GoldenBooks.all();
        if (golden.size() != BOOKS) {
            throw new IllegalStateException("expected " + BOOKS + " golden books");
        }
    }

    @Setup(Level.Invocation)
    public void removeGolden() throws SQLException {
        for (IndexedBook b : golden) {
            datamart.remove(b.bookId());
        }
    }

    @Benchmark
    @OperationsPerInvocation(BOOKS)
    public void oneTransactionPerBook() throws SQLException {
        for (IndexedBook b : golden) {
            datamart.write(b);
        }
    }

    @Benchmark
    @OperationsPerInvocation(BOOKS)
    public void oneBatch() throws SQLException {
        datamart.writeAll(golden);
    }

    @TearDown(Level.Trial)
    public void close() throws IOException, SQLException {
        if (datamart.inconsistentTerms() != 0) {
            throw new IllegalStateException("datamart inconsistent after the benchmark");
        }
        datamart.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
