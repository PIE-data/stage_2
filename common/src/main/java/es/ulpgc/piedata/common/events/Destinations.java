package es.ulpgc.piedata.common.events;

public final class Destinations {
    public static final String INGEST_REQUESTS = "ingest.requests";
    public static final String BOOKS_DOWNLOADED = "books.downloaded";
    public static final String BOOKS_INDEXED = "books.indexed";
    public static final String DLQ = "ActiveMQ.DLQ";

    private Destinations() {}
}
