package es.ulpgc.piedata.bench;

import java.util.Map;

/**
 * Load generator and measurement client for the integration benchmarks (#8, brief section 6.4).
 * It is not a service and is not deployed: it runs next to the deployed system and talks to it
 * only through its public interfaces (HTTP APIs and broker destinations). See
 * {@code docs/INTEGRATION_BENCHMARK.md}.
 *
 * <pre>
 * java -jar bench-client/target/bench-client.jar &lt;command&gt; --name value ...
 *   search      --url URL --queries FILE [--concurrency 1] [--warmup 10] [--duration 60] [--limit 10]
 *   pipeline    --ingest-url URL[,URL...] --ids FILE [--count N] [--rate 0] [--timeout 600]
 *               [--broker tcp://127.0.0.1:61616] [--broker-http http://127.0.0.1:7000]
 *   index-only  --data-dir DIR [--count N] [--rate 0] [--timeout 600] [--broker ...] [--broker-http ...]
 *   queries     --datamart FILE --out FILE [--n 300] [--seed 42] [--min-df 2]
 *   aggregate   --runs DIR --out FILE.csv
 * every measuring command also takes --out DIR (results folder) and [--label NAME]
 * </pre>
 */
public final class BenchApp {

    private BenchApp() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, Object> result;
        try {
            Args args = new Args(argv, 1);
            result = switch (argv[0]) {
                case "search" -> SearchLoad.run(args);
                case "pipeline" -> PipelineLoad.run(args, false);
                case "index-only" -> PipelineLoad.run(args, true);
                case "queries" -> QueryGenerator.run(args);
                case "aggregate" -> Aggregate.run(args);
                default -> throw new IllegalArgumentException("unknown command '" + argv[0] + "'");
            };
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        System.out.println(Output.PRETTY.toJson(result));
        System.exit(0);   // the JMS client leaves non-daemon threads behind
    }

    private static void usage() {
        System.err.println("usage: java -jar bench-client.jar search|pipeline|index-only|queries|aggregate --name value ..."
                + "\n  see docs/INTEGRATION_BENCHMARK.md");
    }
}
