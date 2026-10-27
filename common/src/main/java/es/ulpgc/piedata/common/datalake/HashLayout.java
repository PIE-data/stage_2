package es.ulpgc.piedata.common.datalake;

/**
 * Paths of the Stage 1 {@code hash} datalake layout (Stage 1 SPEC §4), relative to {@code data.dir},
 * with {@code /} separators: {@code datalake/<id6[0:2]>/<id6[2:4]>/<id>.header.txt} and
 * {@code .body.txt}, where {@code id6} is the id zero-padded to 6 digits (1342 → "001342").
 * Shared by Ingestion (writes) and Indexing (reads).
 */
public final class HashLayout {

    private HashLayout() {
    }

    public static String folder(long bookId) {
        String id6 = String.format("%06d", bookId);
        return "datalake/" + id6.substring(0, 2) + "/" + id6.substring(2, 4);
    }

    public static String headerPath(long bookId) {
        return folder(bookId) + "/" + bookId + ".header.txt";
    }

    public static String bodyPath(long bookId) {
        return folder(bookId) + "/" + bookId + ".body.txt";
    }
}
