package es.ulpgc.piedata.common.datalake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptStoreTest {

    @Test
    void hashLayoutPathsAreTheStage1Ones() {
        assertEquals("datalake/00/13/1342.header.txt", HashLayout.headerPath(1342));
        assertEquals("datalake/00/13/1342.body.txt", HashLayout.bodyPath(1342));
        assertEquals("datalake/06/61/66188.body.txt", HashLayout.bodyPath(66188));
        assertEquals("datalake/12/34/1234567.body.txt", HashLayout.bodyPath(1234567));
    }

    @Test
    void writesTheExactStage1LineAndReadsItBack(@TempDir Path dataDir) throws Exception {
        ReceiptStore store = new ReceiptStore(dataDir);
        Receipt r = new Receipt(1342, HashLayout.headerPath(1342), HashLayout.bodyPath(1342), "2026-10-22T10:00:03Z");
        store.write(r);

        Path file = dataDir.resolve("control/ingestion/hash/1342.json");
        assertEquals("{\"book_id\":1342,\"header_path\":\"datalake/00/13/1342.header.txt\","
                + "\"body_path\":\"datalake/00/13/1342.body.txt\",\"ingested_at\":\"2026-10-22T10:00:03Z\"}\n",
                Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(Optional.of(r), store.read(1342));
        assertEquals(List.of(), Files.list(file.getParent()).filter(p -> p.toString().endsWith(".part")).toList());
    }

    @Test
    void missingReceiptMeansNotIngested(@TempDir Path dataDir) {
        assertEquals(Optional.empty(), new ReceiptStore(dataDir).read(7));
        assertEquals(List.of(), new ReceiptStore(dataDir).ids());
    }

    @Test
    void idsAreSortedNumericallyAndIgnoreOtherFiles(@TempDir Path dataDir) throws Exception {
        ReceiptStore store = new ReceiptStore(dataDir);
        for (long id : new long[] {100, 9, 20}) {
            store.write(new Receipt(id, HashLayout.headerPath(id), HashLayout.bodyPath(id), "2026-10-22T10:00:03Z"));
        }
        Files.writeString(store.file(5).resolveSibling("5.json.part"), "unfinished");
        assertEquals(List.of(9L, 20L, 100L), store.ids());
    }
}
