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
import java.time.Clock;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cost of writing one already-indexed book into SQLite: one transaction that removes the previous
 * version (df -1, delete postings), writes the metadata row, the postings and df +1, then commits.
 *
 * <p>The datamart is first filled with {@link BenchData#preload()}: the 20 golden books, plus a
 * whole datalake when {@code -Dbench.datadir} is given, because write cost grows with the index.
 * {@code cacheMb} compares SQLite's tiny default page cache (2 MiB) with the configured one.
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=DatamartWriteBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatamartWriteBenchmark {

    @Param({"8708", "36020"})
    public long bookId;

    /** SQLite page cache of the connection, MiB. */
    @Param({"2", "256"})
    public int cacheMb;

    private Path dir;
    private Datamart datamart;
    private IndexedBook book;

    @Setup(Level.Trial)
    public void open() throws IOException, SQLException {
        dir = Files.createTempDirectory("datamart-bench");
        datamart = Datamart.open(dir.resolve("datamart.db"), Clock.systemUTC(), cacheMb);
        long start = System.nanoTime();
        int books = datamart.rebuild(BenchData.preload());
        System.out.printf("%n[setup] datamart filled with %d books in %.1f s (cache %d MiB)%n",
                books, (System.nanoTime() - start) / 1e9, cacheMb);
        book = GoldenBooks.all().stream().filter(b -> b.bookId() == bookId).findFirst().orElseThrow();
    }

    @Benchmark
    public void write() throws SQLException {
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
