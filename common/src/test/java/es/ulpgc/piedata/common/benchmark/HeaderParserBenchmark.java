package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.metadata.BookMetadata;
import es.ulpgc.piedata.common.metadata.HeaderParser;
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
 * Cost of extracting metadata from one Gutenberg header.
 * Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=HeaderParserBenchmark}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HeaderParserBenchmark {

    /** 45484: short header; 10056: multi-line title and authors. */
    @Param({"45484", "10056"})
    public long bookId;

    private String header;
    private HeaderParser parser;

    @Setup
    public void load() {
        header = SpecFiles.goldenHeader(bookId);
        parser = HeaderParser.standard();
    }

    @Benchmark
    public BookMetadata parse() {
        return parser.parse(header);
    }
}
