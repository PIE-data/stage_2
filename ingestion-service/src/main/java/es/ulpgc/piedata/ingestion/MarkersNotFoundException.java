package es.ulpgc.piedata.ingestion;

public class MarkersNotFoundException extends RuntimeException {

    public MarkersNotFoundException(String message) {
        super(message);
    }
}