package es.ulpgc.piedata.indexing.benchmark;

import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.indexing.IndexingService;
import es.ulpgc.piedata.indexing.TestWorkspace;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Books (re-)indexed per second by ONE Indexing process when several threads call it at once
 * (what Jetty request threads or broker consumer threads do): read from the datalake, index, write.
 * <ul>
 *   <li>{@code oneBookPerCall}: {@code update(id)}, one transaction per book;</li>
 *   <li>{@code batchOf20}: {@code updateAll(20 ids)}, the 20 books tokenized in parallel and written
 *       in one transaction (how the broker consumer works); score per book.</li>
 * </ul>
 * Threading experiment (brief §6.2): {@code -Djmh.threads=1, 2, 4, 8}, see
 * {@code scripts/bench-indexing.sh}. Iterations last 20 s so that each one covers many books of
 * very different sizes (shorter ones gave ±25 % errors on the tier-1000 mix).
 *
 * <p>Data: by default the 20 golden books in a temporary folder. With {@code -Dbench.datadir=<folder>}
 * (hash-layout datalake + receipts, e.g. Stage 1 tier 1000) every book of that folder is used and
 * the datamart is written to {@code <folder>/datamarts/}. Books are taken round-robin.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(time = 20)
@Measurement(time = 20)
public class IndexingThroughputBenchmark {

    private static final int BATCH = 20;

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
        service = new IndexingService(dataDir);
        IndexingService.RebuildResult r = service.rebuild();          // start from a full datamart
        System.out.printf("%n[setup] rebuild of %d books: %.1f s = %.1f books/s%n",
                r.booksProcessed(), r.elapsedMs() / 1000.0, r.booksProcessed() * 1000.0 / r.elapsedMs());
    }

    private long nextId() {
        return ids[(int) (next.getAndIncrement() % ids.length)];
    }

    @Benchmark
    public IndexingService.UpdateResult oneBookPerCall() {
        return service.update(nextId());
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public int batchOf20() {
        List<Long> batch = new ArrayList<>(BATCH);
        for (int i = 0; i < BATCH; i++) {
            batch.add(nextId());
        }
        return service.updateAll(batch);
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
