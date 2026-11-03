package es.ulpgc.piedata.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @Test
    void percentilesAreNearestRank() {
        double[] v = new double[100];
        for (int i = 0; i < 100; i++) {
            v[i] = 100 - i;                       // 100..1, unsorted on purpose
        }
        Stats s = Stats.of(v);
        assertEquals(100, s.n());
        assertEquals(50.5, s.mean(), 1e-9);
        assertEquals(50, s.p50());
        assertEquals(95, s.p95());
        assertEquals(99, s.p99());
        assertEquals(1, s.min());
        assertEquals(100, s.max());
        assertEquals(29.011, s.sd(), 1e-3);       // sample standard deviation of 1..100
    }

    @Test
    void emptyAndSingleSamples() {
        assertTrue(Double.isNaN(Stats.of(new double[0]).mean()));
        Stats one = Stats.of(new double[]{7});
        assertEquals(7, one.p95());
        assertEquals(0, one.sd());
    }

    @Test
    void queryGenerationIsReproducibleAndMixesFrequencies() {
        List<String> terms = List.of("the", "and", "of", "love", "ship", "whale", "zephyr", "quixotic", "abaft");
        List<String> a = QueryGenerator.generate(terms, 30, 42);
        assertEquals(a, QueryGenerator.generate(terms, 30, 42));
        assertTrue(List.of("the", "and", "of").contains(a.get(0).split(" ")[0]), a.get(0));
        assertTrue(List.of("zephyr", "quixotic", "abaft").contains(a.get(2).split(" ")[0]), a.get(2));
    }

    @Test
    void queryLinesBecomeQueryStrings() {
        assertEquals("q=white+whale&limit=10", SearchLoad.queryString("white whale", 10));
        assertEquals("q=love&author=austen&limit=5", SearchLoad.queryString("q=love&author=austen", 5));
        assertEquals("q=x&limit=3", SearchLoad.queryString("q=x&limit=3", 10));
    }

    @Test
    void ingestionThroughputFromBrokerSamples() {
        List<String> rows = List.of(
                "0,books.downloaded,0,10,10,0,1.0",
                "1000,books.downloaded,0,14,14,0,1.0",
                "1000,ingest.requests,5,20,15,1,2.0",
                "2000,books.downloaded,0,20,20,0,1.0",
                "3000,books.downloaded,0,20,20,0,1.0");
        assertEquals(5.0, PipelineLoad.ingestionThroughput(rows));   // 10 books between t=0 and t=2 s
    }

    @Test
    void flattenKeepsOnlyNumbers() {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                "{\"label\":\"x\",\"options\":{\"n\":1},\"requests\":10,\"latency_ms\":{\"p95\":3.5,\"note\":\"a\"}}")
                .getAsJsonObject();
        Map<String, List<Double>> m = new TreeMap<>();
        Aggregate.flatten("", o, m);
        assertEquals(Map.of("latency_ms.p95", List.of(3.5), "requests", List.of(10.0)), m);
    }
}
