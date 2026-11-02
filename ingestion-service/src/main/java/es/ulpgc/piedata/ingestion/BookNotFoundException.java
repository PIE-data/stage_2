package es.ulpgc.piedata.ingestion;

public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(long bookId) {
        super("book not found on Gutenberg (404): " + bookId);
    }
}