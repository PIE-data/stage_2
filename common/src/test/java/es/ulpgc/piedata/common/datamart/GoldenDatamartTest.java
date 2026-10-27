package es.ulpgc.piedata.common.datamart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The index built by the Java indexer and stored in SQLite must be exactly the Stage 1 index:
 * canonical export of the 20 golden books = {@code spec/golden/expected.sha256} (Stage 1 SPEC §7).
 */
class GoldenDatamartTest {

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void inMemoryExportMatchesStage1() throws Exception {
        assertEquals(GoldenBooks.expectedCanonicalSha256(), sha256(CanonicalExport.of(GoldenBooks.all())));
    }

    @Test
    void oneBookPerTransactionMatchesStage1(@TempDir Path dir) throws Exception {
        try (Datamart d = Datamart.open(dir.resolve("datamart.db"))) {
            for (IndexedBook b : GoldenBooks.all()) {
                d.write(b);
            }
            assertEquals(GoldenBooks.expectedCanonicalSha256(), sha256(d.exportCanonical()));
            assertEquals(0, d.inconsistentTerms());
            assertEquals(20, d.stats().books());
        }
    }

    @Test
    void oneBatchInAnyOrderMatchesStage1(@TempDir Path dir) throws Exception {
        List<IndexedBook> shuffled = new ArrayList<>(GoldenBooks.all());
        Collections.reverse(shuffled);
        try (Datamart d = Datamart.open(dir.resolve("datamart.db"))) {
            d.writeAll(shuffled);
            assertEquals(GoldenBooks.expectedCanonicalSha256(), sha256(d.exportCanonical()));
        }
    }

    @Test
    void reindexingEverythingChangesNothing(@TempDir Path dir) throws Exception {
        try (Datamart d = Datamart.open(dir.resolve("datamart.db"))) {
            d.writeAll(GoldenBooks.all());
            Datamart.Stats before = d.stats();
            d.writeAll(GoldenBooks.all());                 // duplicate delivery of every book
            assertEquals(GoldenBooks.expectedCanonicalSha256(), sha256(d.exportCanonical()));
            assertEquals(before.books(), d.stats().books());
            assertEquals(before.terms(), d.stats().terms());
            assertEquals(before.postings(), d.stats().postings());
            assertEquals(0, d.inconsistentTerms());
        }
    }
}
