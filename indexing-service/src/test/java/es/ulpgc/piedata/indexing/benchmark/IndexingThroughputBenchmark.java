package es.ulpgc.piedata.indexing.benchmark;

import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.indexing.IndexingService;
import es.ulpgc.piedata.indexing.TestWorkspace;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Books indexed per second by ONE Indexing process when several threads call it at the same time
 * (what Jetty request threads or broker consumer threads do). Each call reads a book from the
 * datalake, indexes it (in parallel across threads) and writes it (one writer at a time).
 *
 * <p>Threading experiment (brief §6.2): run with {@code -Djmh.threads=1, 2, 4, 8}; see
 * {@code scripts/bench-indexing.sh}. {@code cacheMb} compares SQLite's default page cache with the
 * configured one.
 *
 * <p>Data: by default the 20 golden books in a temporary folder. With {@code -Dbench.datadir=<folder>}
 * (hash-layout datalake + receipts, e.g. Stage 1 tier 1000) every book of that folder is used and
 * the datamart is written to {@code <folder>/datamarts/}. Books are taken round-robin, so the score
 * is the average over the whole mix of book sizes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class IndexingThroughputBenchmark {

    @Param({"2", "256"})
    public int cacheMb;

    private Path dataDir;
    private boolean temporary;
    private IndexingService service;
    private long[] ids;
    private final AtomicLong next = new AtomicLong();

    @Setup(Level.Trial)
    public void prepare() throws IOException, SQLException {
        String external = System.getProperty("bench.datadir", "");
        temporary = external.isBlank();
        if (temporary) {
            dataDir = Files.createTempDirectory("indexing-bench");
            TestWorkspace.ingestGolden(dataDir);
        } else {
            dataDir = Path.of(external);
        }
        ids = new ReceiptStore(dataDir).ids().stream().mapToLong(Long::longValue).toArray();
        service = new IndexingService(dataDir, cacheMb);
        IndexingService.RebuildResult r = service.rebuild();          // start from a full datamart
        System.out.printf("%n[setup] rebuild of %d books: %.1f s = %.1f books/s (cache %d MiB)%n",
                r.booksProcessed(), r.elapsedMs() / 1000.0, r.booksProcessed() * 1000.0 / r.elapsedMs(), cacheMb);
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
        if (temporary) {
            try (Stream<Path> files = Files.walk(dataDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
