package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.datamart.BookIndexer;
import es.ulpgc.piedata.common.datamart.GoldenBooks;
import es.ulpgc.piedata.common.datamart.IndexedBook;
import es.ulpgc.piedata.common.text.SpecFiles;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * CPU cost of turning one book into an {@link IndexedBook}: header parsing + tokenizing +
 * grouping positions per term + SHA-256 of the body. No disk, no database.
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=BookIndexerBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BookIndexerBenchmark {

    /** 8708: ~2.7 k tokens; 36020: ~129 k tokens (largest golden book). */
    @Param({"8708", "36020"})
    public long bookId;

    private String header;
    private byte[] body;
    private BookIndexer indexer;

    @Setup
    public void load() {
        header = SpecFiles.goldenHeader(bookId);
        body = GoldenBooks.body(bookId);
        indexer = BookIndexer.standard();
    }

    @Benchmark
    public IndexedBook index() {
        return indexer.index(bookId, header, body, "h", "b", "2026-10-01T00:00:00Z");
    }
}
