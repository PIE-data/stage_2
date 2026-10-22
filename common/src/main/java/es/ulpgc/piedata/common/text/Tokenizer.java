package es.ulpgc.piedata.common.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Java port of the Stage 1 tokenizer (Stage 1 SPEC §3). Must produce exactly the same tokens as
 * the Python, Node and Go versions: the test {@code GoldenTokensTest} checks it against
 * {@code spec/golden/tokens_20.jsonl}.
 *
 * <p>Pipeline, in this order:
 * <ol>
 *   <li>{@link #normalize}: NFKC, locale-independent lower case, accents removed
 *       (NFD, drop non-spacing marks, NFC);</li>
 *   <li>{@link #extractTokens}: a state machine over Unicode code points. Letters (Lu, Ll, Lt, Lm,
 *       Lo) and decimal digits (Nd) form tokens; an apostrophe (U+0027 or U+2019) stays inside a
 *       token only when both neighbours are word characters; everything else ends the token;</li>
 *   <li>{@link #tokenize}: positions are the ordinal of each token <em>before</em> filtering; then
 *       tokens shorter than 2 or longer than 40 code points, digit-only tokens and stop words are
 *       dropped (their positions are not reused).</li>
 * </ol>
 * Lengths are counted in code points, never in Java {@code char}s, so characters outside the
 * Basic Multilingual Plane behave as in the other languages.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Tokenizer {

    /** Classpath location of the Stage 1 stop-word list (copied from {@code spec/}). */
    public static final String STOPWORDS_RESOURCE = "spec/stopwords_en.txt";

    static final int MIN_TOKEN_LENGTH = 2;
    static final int MAX_TOKEN_LENGTH = 40;

    private final Set<String> stopwords;

    public Tokenizer(Set<String> stopwords) {
        this.stopwords = Set.copyOf(stopwords);
    }

    /** Tokenizer with the Stage 1 English stop words. */
    public static Tokenizer english() {
        return EnglishHolder.INSTANCE;
    }

    /** A token kept after filtering, with its position in the unfiltered token stream. */
    public record Token(String term, int position) {
    }

    /** SPEC §3.1. The order of the three steps is part of the contract. */
    public static String normalize(String text) {
        // 1. NFKC: ligatures, full-width forms, circled digits... become plain characters.
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // 2. Lower case independent of the machine's language settings (Locale.ROOT), like
        //    Python str.lower() - NOT a case fold: "ß" stays "ß".
        s = s.toLowerCase(Locale.ROOT);
        // 3. Remove accents: decompose, drop non-spacing marks (category Mn), recompose.
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints()
                .filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
                .forEach(out::appendCodePoint);
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }

    /** SPEC §3.2: every token, in document order, before any filtering. */
    public static List<String> extractTokens(String text) {
        int[] cps = normalize(text).codePoints().toArray();
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (isWordChar(cp)) {
                buf.appendCodePoint(cp);
                continue;
            }
            if (isApostrophe(cp)
                    && buf.length() > 0
                    && i > 0 && isWordChar(cps[i - 1])
                    && i + 1 < cps.length && isWordChar(cps[i + 1])) {
                buf.appendCodePoint(cp);
                continue;
            }
            if (buf.length() > 0) {
                tokens.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }
        return tokens;
    }

    /** SPEC §3.3: kept tokens with their pre-filter position. */
    public List<Token> tokenize(String text) {
        List<String> all = extractTokens(text);
        List<Token> kept = new ArrayList<>(all.size() / 2);
        for (int position = 0; position < all.size(); position++) {
            String token = all.get(position);
            if (keep(token)) {
                kept.add(new Token(token, position));
            }
        }
        return kept;
    }

    /**
     * Terms of a search query: the same pipeline as documents, then duplicates removed, order
     * kept (Stage 1 SPEC §1.1). A query whose terms are all discarded returns an empty list,
     * which must match nothing.
     */
    public List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (Token t : tokenize(query)) {
            if (!terms.contains(t.term())) {
                terms.add(t.term());
            }
        }
        return terms;
    }

    private boolean keep(String token) {
        int length = token.codePointCount(0, token.length());
        if (length < MIN_TOKEN_LENGTH || length > MAX_TOKEN_LENGTH) {
            return false;
        }
        if (isAllDigits(token)) {
            return false;
        }
        return !stopwords.contains(token);
    }

    static boolean isWordChar(int cp) {
        switch (Character.getType(cp)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.DECIMAL_DIGIT_NUMBER:
                return true;
            default:
                return false;
        }
    }

    private static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’';
    }

    private static boolean isAllDigits(String token) {
        return token.codePoints().allMatch(cp -> Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER);
    }

    /**
     * Reads a stop-word list: one term per line, UTF-8; blank lines and lines starting with
     * {@code #} are ignored; surrounding whitespace is trimmed (SPEC §3.3).
     */
    public static Set<String> loadStopwords(InputStream in) {
        Set<String> terms = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    terms.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read stop words", e);
        }
        return terms;
    }

    /** Loaded on first use, once per JVM. */
    private static final class EnglishHolder {
        static final Tokenizer INSTANCE = load();

        private static Tokenizer load() {
            InputStream in = Tokenizer.class.getClassLoader().getResourceAsStream(STOPWORDS_RESOURCE);
            if (in == null) {
                throw new IllegalStateException(STOPWORDS_RESOURCE + " not found on the classpath");
            }
            return new Tokenizer(loadStopwords(in));
        }
    }
}
