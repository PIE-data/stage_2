package es.ulpgc.piedata.ingestion.benchmark;

import es.ulpgc.piedata.ingestion.split.BookSplitter;
import es.ulpgc.piedata.ingestion.split.MarkersNotFoundException;
import es.ulpgc.piedata.ingestion.split.SplitResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BookSplitterBenchmark {

    @Param({"1342", "8527"})
    public long bookId;

    private String rawBook;
    private BookSplitter splitter;

    @Setup
    public void setup() throws IOException {
        Path goldenDir = Files.isDirectory(Path.of("..", "spec", "golden"))
                ? Path.of("..", "spec", "golden")
                : Path.of("spec", "golden");
        Path file = goldenDir.resolve(bookId + ".txt");
        rawBook = Files.readString(file, StandardCharsets.UTF_8);
        splitter = new BookSplitter();
    }

    @Benchmark
    public SplitResult splitAndClean() throws MarkersNotFoundException {
        return splitter.split(rawBook);
    }
}