package es.ulpgc.piedata.bench;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Summary of a sample (latencies in ms, or one value per repeated run). */
record Stats(int n, double mean, double sd, double min, double p50, double p90, double p95,
             double p99, double max) {

    static Stats of(double[] values) {
        if (values.length == 0) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
        double[] v = values.clone();
        Arrays.sort(v);
        double sum = 0;
        for (double x : v) {
            sum += x;
        }
        double mean = sum / v.length;
        double sq = 0;
        for (double x : v) {
            sq += (x - mean) * (x - mean);
        }
        double sd = v.length > 1 ? Math.sqrt(sq / (v.length - 1)) : 0.0;   // sample standard deviation
        return new Stats(v.length, mean, sd, v[0], percentile(v, 50), percentile(v, 90),
                percentile(v, 95), percentile(v, 99), v[v.length - 1]);
    }

    /** Nearest-rank percentile of a SORTED array: the smallest value with at least p% of the sample at or below it. */
    static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    /** For JSON output, values rounded to 0.01. */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("mean", r(mean));
        m.put("sd", r(sd));
        m.put("min", r(min));
        m.put("p50", r(p50));
        m.put("p90", r(p90));
        m.put("p95", r(p95));
        m.put("p99", r(p99));
        m.put("max", r(max));
        return m;
    }

    static Object r(double x) {
        return Double.isNaN(x) ? null : Math.round(x * 100.0) / 100.0;
    }
}
