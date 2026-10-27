package es.ulpgc.piedata.indexing.benchmark;

import es.ulpgc.piedata.indexing.IndexingService;
import es.ulpgc.piedata.indexing.TestWorkspace;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Books indexed per second by ONE Indexing process when several threads call it at the same time
 * (what Jetty request threads or broker consumer threads do). Each call reads a book from the
 * datalake, indexes it (in parallel across threads) and writes it (one writer at a time).
 *
 * <p>Threading experiment (brief §6.2): run with {@code -Djmh.threads=1, 2, 4, 8}; see
 * {@code scripts/bench-indexing.sh}. Books are taken round-robin from the 20 golden books
 * (2 k to 129 k tokens each), so the score is the average over that mix.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class IndexingThroughputBenchmark {

    private Path dataDir;
    private IndexingService service;
    private long[] ids;
    private final AtomicLong next = new AtomicLong();

    @Setup(Level.Trial)
    public void prepare() throws IOException, SQLException {
        dataDir = Files.createTempDirectory("indexing-bench");
        List<Long> golden = TestWorkspace.ingestGolden(dataDir);
        ids = golden.stream().mapToLong(Long::longValue).toArray();
        service = new IndexingService(dataDir);
        service.rebuild();                                  // start from a full datamart
    }

    @Benchmark
    public IndexingService.UpdateResult update() {
        long id = ids[(int) (next.getAndIncrement() % ids.length)];
        return service.update(id);
    }

    @TearDown(Level.Trial)
    public void cleanUp() throws IOException, SQLException {
        if (service.status().booksIndexed() != ids.length) {
            throw new IllegalStateException("unexpected number of books after the benchmark");
        }
        service.close();
        try (Stream<Path> files = Files.walk(dataDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
