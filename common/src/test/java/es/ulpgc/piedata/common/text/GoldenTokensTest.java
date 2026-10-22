package es.ulpgc.piedata.common.text;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Java tokenizer must reproduce the Stage 1 conformance fixture exactly
 * ({@code spec/golden/tokens_20.jsonl}, Stage 1 SPEC §3.4): for every golden book the number of
 * raw tokens, the number of kept tokens and the SHA-256 of the kept tokens joined by "\n".
 */
class GoldenTokensTest {

    static List<JsonObject> fixture() throws IOException {
        return Files.readAllLines(SpecFiles.golden().resolve("tokens_20.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .collect(Collectors.toList());
    }

    @ParameterizedTest(name = "book {0}")
    @MethodSource("fixture")
    void matchesStage1(JsonObject expected) throws NoSuchAlgorithmException {
        long bookId = expected.get("book_id").getAsLong();
        String body = SpecFiles.goldenBody(bookId);

        List<String> raw = Tokenizer.extractTokens(body);
        List<String> kept = Tokenizer.english().tokenize(body).stream()
                .map(Tokenizer.Token::term)
                .collect(Collectors.toList());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", kept).getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected.get("n_tokens_raw").getAsInt(), raw.size(), "raw tokens of " + bookId);
        assertEquals(expected.get("n_tokens_kept").getAsInt(), kept.size(), "kept tokens of " + bookId);
        assertEquals(expected.get("sha256_tokens").getAsString(), sha, "token hash of " + bookId);
    }
}
