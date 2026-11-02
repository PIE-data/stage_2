package es.ulpgc.piedata.ingestion.split;

public class MarkersNotFoundException extends RuntimeException {

    public MarkersNotFoundException(String message) {
        super(message);
    }
}