package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.datamart.GoldenBooks;
import es.ulpgc.piedata.common.datamart.IndexedBook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cost of writing a NEW book (first indexing: no previous version to remove), the common case in
 * the pipeline, on a datamart already filled by {@link BenchData#preload()} (cache 256 MiB).
 * Before each call the book is removed again, outside the measured time.
 * Compare with {@link DatamartWriteBenchmark}, which measures a re-index.
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=DatamartInsertBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatamartInsertBenchmark {

    @Param({"8708", "36020"})
    public long bookId;

    private Path dir;
    private Datamart datamart;
    private IndexedBook book;

    @Setup(Level.Trial)
    public void open() throws IOException, SQLException {
        dir = Files.createTempDirectory("datamart-insert-bench");
        datamart = Datamart.open(dir.resolve("datamart.db"));
        long start = System.nanoTime();
        int books = datamart.rebuild(BenchData.preload());
        System.out.printf("%n[setup] datamart filled with %d books in %.1f s (cache %d MiB)%n",
                books, (System.nanoTime() - start) / 1e9, Datamart.DEFAULT_CACHE_MB);
        book = GoldenBooks.all().stream().filter(b -> b.bookId() == bookId).findFirst().orElseThrow();
    }

    @Setup(Level.Invocation)
    public void removeBook() throws SQLException {
        datamart.remove(bookId);
    }

    @Benchmark
    public void insert() throws SQLException {
        datamart.write(book);
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
