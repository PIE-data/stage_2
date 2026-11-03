package es.ulpgc.piedata.ingestion.download;

public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(long bookId) {
        super("book not found on Gutenberg (404): " + bookId);
    }
}