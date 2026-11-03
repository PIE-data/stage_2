package es.ulpgc.piedata.bench;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds a reproducible query workload from a datamart: terms are drawn (fixed seed) from three
 * equal groups by document frequency, frequent / medium / rare, so that the workload mixes cheap
 * and expensive queries. 70 % of the queries have one term, 30 % two terms of the same group.
 * Writes one query per line, preceded by a comment with the source and the seed.
 */
final class QueryGenerator {

    private QueryGenerator() {
    }

    static Map<String, Object> run(Args args) throws Exception {
        Path datamart = args.getPath("datamart");
        int n = args.getInt("n", 300);
        long seed = args.getInt("seed", 42);
        int minDf = args.getInt("min-df", 2);
        Path out = args.getPath("out");
        args.rejectUnknown();
        if (!java.nio.file.Files.isRegularFile(datamart)) {
            throw new IllegalArgumentException("datamart not found: " + datamart);
        }

        List<String> terms = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + datamart.toAbsolutePath());   // SELECT only
             PreparedStatement p = c.prepareStatement("SELECT term FROM terms WHERE df >= ? ORDER BY df DESC, term")) {
            p.setInt(1, minDf);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    terms.add(rs.getString(1));
                }
            }
        }
        List<String> queries = generate(terms, n, seed);
        StringBuilder sb = new StringBuilder("# generated from " + datamart.getFileName() + " with seed=" + seed
                + ", min-df=" + minDf + ", " + terms.size() + " candidate terms\n");
        for (String q : queries) {
            sb.append(q).append('\n');
        }
        java.nio.file.Files.createDirectories(out.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(out, sb.toString());
        return Map.of("queries", queries.size(), "candidate_terms", terms.size(), "out", out.toString());
    }

    /** {@code termsByDfDesc}: terms sorted by document frequency, most frequent first. */
    static List<String> generate(List<String> termsByDfDesc, int n, long seed) {
        if (termsByDfDesc.size() < 3) {
            throw new IllegalArgumentException("the datamart has fewer than 3 usable terms");
        }
        int third = termsByDfDesc.size() / 3;
        List<List<String>> groups = List.of(
                termsByDfDesc.subList(0, third),
                termsByDfDesc.subList(third, 2 * third),
                termsByDfDesc.subList(2 * third, termsByDfDesc.size()));
        Random rnd = new Random(seed);
        List<String> queries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<String> g = groups.get(i % 3);
            String q = g.get(rnd.nextInt(g.size()));
            if (rnd.nextDouble() < 0.3) {
                q = q + " " + g.get(rnd.nextInt(g.size()));
            }
            queries.add(q);
        }
        return queries;
    }
}
