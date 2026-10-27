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
 * Cost of writing one already-indexed book into SQLite: one transaction that removes the previous
 * version (df -1, delete postings), writes the metadata row, the postings and df +1, then commits.
 * The datamart already holds the 20 golden books, so every call is a realistic re-index.
 * Includes the disk sync of the commit (WAL, synchronous=NORMAL): run on the real target disk.
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=DatamartWriteBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatamartWriteBenchmark {

    @Param({"8708", "36020"})
    public long bookId;

    private Path dir;
    private Datamart datamart;
    private IndexedBook book;

    @Setup(Level.Trial)
    public void open() throws IOException, SQLException {
        dir = Files.createTempDirectory("datamart-bench");
        datamart = Datamart.open(dir.resolve("datamart.db"));
        datamart.writeAll(GoldenBooks.all());
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
