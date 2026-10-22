package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.text.SpecFiles;
import es.ulpgc.piedata.common.text.Tokenizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Example JMH benchmark - copy this structure for the benchmarks of your own module.
 *
 * <p>Rules (brief section 6.3): benchmark classes live under {@code src/test/java/.../benchmark},
 * never in production code; warm-up, iterations, forks, threads and heap come from the parent pom
 * ({@code jmh.*} properties) so that every module is measured the same way.
 *
 * <p>Run: {@code mvn -pl common test-compile exec:exec -Djmh.include=TokenizerBenchmark}
 * (results in {@code results/jmh/common.csv}).
 *
 * <p>Measures the whole tokenizer pipeline (normalize + extract + filter) on a short and on a long
 * golden book. Return values are handed back to JMH so the JIT cannot remove the work.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class TokenizerBenchmark {

    /** 8708: ~2.7 k tokens; 36020: ~129 k tokens (largest golden book). */
    @Param({"8708", "36020"})
    public long bookId;

    private String body;
    private Tokenizer tokenizer;

    @Setup
    public void load() {
        body = SpecFiles.goldenBody(bookId);   // outside the measured time
        tokenizer = Tokenizer.english();
    }

    @Benchmark
    public List<Tokenizer.Token> tokenize() {
        return tokenizer.tokenize(body);
    }

    @Benchmark
    public String normalizeOnly() {
        return Tokenizer.normalize(body);
    }
}
