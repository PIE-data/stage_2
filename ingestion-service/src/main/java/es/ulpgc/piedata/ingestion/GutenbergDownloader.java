package es.ulpgc.piedata.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class GutenbergDownloader {

    private static final String USER_AGENT = "ULPGC-BigData-Stage1/PIE-data (+https://github.com/PIE-data/stage_1)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final long[] BACKOFF_MS = {1000L, 2000L, 4000L};

    private final String baseUrl;
    private final HttpClient client;

    public GutenbergDownloader(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public GutenbergDownloader(String baseUrl, HttpClient client) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = client;
    }

    public String download(long bookId) throws IOException, InterruptedException {
        String url = baseUrl + "/cache/epub/" + bookId + "/pg" + bookId + ".txt";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(READ_TIMEOUT)
                .GET()
                .build();

        Exception lastError = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return decodeUtf8WithReplacement(response.body());
                }
                if (status == 404) {
                    throw new BookNotFoundException(bookId);
                }
                if (status < 500) {
                    throw new IOException("HTTP " + status + " downloading book " + bookId);
                }
                lastError = new IOException("HTTP " + status + " downloading book " + bookId);
            } catch (IOException e) {
                lastError = e;
            }

            if (attempt < BACKOFF_MS.length) {
                Thread.sleep(BACKOFF_MS[attempt]);
            }
        }

        throw new IOException("Failed to download book " + bookId + " after retries", lastError);
    }

    private static String decodeUtf8WithReplacement(byte[] bytes) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}