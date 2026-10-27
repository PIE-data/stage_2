package es.ulpgc.piedata.common.benchmark;

import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.common.datamart.BookIndexer;
import es.ulpgc.piedata.common.datamart.GoldenBooks;
import es.ulpgc.piedata.common.datamart.IndexedBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Data used to fill the datamart before a benchmark. By default the 20 golden books. With
 * {@code -Dbench.datadir=<folder>} (a data folder holding a hash-layout datalake and its receipts,
 * e.g. the Stage 1 tier 1000), every book of that folder is added: write costs depend on how big
 * the index already is, and 20 books is far smaller than a real corpus.
 */
public final class BenchData {

    private BenchData() {
    }

    /** The external data folder, or null when the property is absent or empty. */
    public static Path externalDataDir() {
        String v = System.getProperty("bench.datadir", "");
        return v.isBlank() ? null : Path.of(v);
    }

    /** Golden books, then every book of the external folder; read and indexed lazily. */
    public static Iterable<IndexedBook> preload() {
        List<IndexedBook> golden = GoldenBooks.all();
        Path dir = externalDataDir();
        if (dir == null) {
            return golden;
        }
        ReceiptStore receipts = new ReceiptStore(dir);
        List<Long> ids = receipts.ids();
        BookIndexer indexer = BookIndexer.standard();
        return () -> new Iterator<>() {
            private final Iterator<IndexedBook> first = golden.iterator();
            private int next = 0;

            @Override
            public boolean hasNext() {
                return first.hasNext() || next < ids.size();
            }

            @Override
            public IndexedBook next() {
                if (first.hasNext()) {
                    return first.next();
                }
                long id = ids.get(next++);
                Receipt r = receipts.read(id).orElseThrow();
                try {
                    return indexer.index(dir, r);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** Number of distinct books {@link #preload()} yields (golden ids may also be in the folder). */
    public static long count() {
        Path dir = externalDataDir();
        List<Long> ids = new ArrayList<>(GoldenBooks.ids());
        if (dir != null) {
            new ReceiptStore(dir).ids().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        }
        return ids.size();
    }
}
