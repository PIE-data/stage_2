package es.ulpgc.piedata.common.text;

import es.ulpgc.piedata.common.text.Tokenizer.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests derived from the Stage 1 SPEC §3 (same cases as the Python
 * {@code test_tokenizer.py}). Expected values come from reading the spec, not from running the code.
 */
class TokenizerTest {

    private static final Tokenizer SMALL = new Tokenizer(Set.of("the", "a", "i", "don't", "is", "and", "of"));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "café           | cafe",
            "naïve          | naive",
            "Les Misérables | les miserables",
            "ﬁnd the ﬂow    | find the flow",     // NFKC expands ligatures
            "①②③            | 123",               // NFKC expands circled digits
            "ＡＢＣ          | abc",               // NFKC folds full-width forms
            "straße         | straße",            // lower case, not case folding
            "ÅNGSTRÖM       | angstrom",
    })
    void normalize(String raw, String expected) {
        assertEquals(expected, Tokenizer.normalize(raw));
    }

    @Test
    void lowerCaseDoesNotDependOnTheMachineLanguage() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));  // Turkish: I -> ı
            assertEquals("title", Tokenizer.normalize("TITLE"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void apostrophesJoinOnlyBetweenWordCharacters() {
        assertEquals(List.of("don't"), Tokenizer.extractTokens("don't"));
        assertEquals(List.of("rock'n'roll"), Tokenizer.extractTokens("rock'n'roll"));
        assertEquals(List.of("tis", "the", "season"), Tokenizer.extractTokens("'tis the season"));
        assertEquals(List.of("the", "dogs", "bones"), Tokenizer.extractTokens("the dogs' bones"));
        assertEquals(List.of("a", "b"), Tokenizer.extractTokens("a''b"));
        assertEquals(List.of("don’t"), Tokenizer.extractTokens("don’t"));
    }

    @Test
    void separatorsAndDigits() {
        assertEquals(List.of("hello", "world"), Tokenizer.extractTokens("Hello, world!"));
        assertEquals(List.of("one", "two"), Tokenizer.extractTokens("one--two"));
        assertEquals(List.of("line", "break"), Tokenizer.extractTokens("line\nbreak"));
        assertEquals(List.of("chapter12"), Tokenizer.extractTokens("chapter12"));
        assertEquals(List.of("1984"), Tokenizer.extractTokens("1984"));
        assertEquals(List.of(), Tokenizer.extractTokens(""));
        assertEquals(List.of(), Tokenizer.extractTokens("!!! ??? ..."));
    }

    @Test
    void charactersOutsideTheBasicPlaneAreSingleCodePoints() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A: two Java chars, one code point; NFKC -> "a".
        assertEquals(List.of("abc", "x"), Tokenizer.extractTokens("𝐀𝐁𝐂 x"));
    }

    @Test
    void extractTokensIsBeforeFiltering() {
        assertEquals(List.of("the", "a", "1234"), Tokenizer.extractTokens("the a 1234"));
    }

    @Test
    void filtering() {
        assertEquals(List.of(), SMALL.tokenize("a I x"));                                // < 2
        assertEquals(List.of(), SMALL.tokenize("x".repeat(41)));                         // > 40
        assertEquals(List.of(new Token("x".repeat(40), 0)), SMALL.tokenize("x".repeat(40)));
        assertEquals(List.of(new Token("xx", 0)), SMALL.tokenize("xx"));
        assertEquals(List.of(), SMALL.tokenize("12345"));                                // digits only
        assertEquals(List.of(), SMALL.tokenize("①②③"));                                  // -> "123"
        assertEquals(List.of(new Token("chapter12", 0)), SMALL.tokenize("chapter12"));
        assertEquals(List.of(), SMALL.tokenize("the of and"));                           // stop words
    }

    @Test
    void positionsAreAssignedBeforeFiltering() {
        assertEquals(List.of(new Token("cat", 1), new Token("dog", 3)), SMALL.tokenize("the cat the dog"));
        assertEquals(List.of(new Token("house", 3)), SMALL.tokenize("a 1234 the house"));
    }

    @Test
    void queryTermsAreNormalizedFilteredAndDeduplicated() {
        assertEquals(List.of("whale", "whales"), SMALL.queryTerms("The Whale whale WHALES"));
        assertEquals(List.of(), SMALL.queryTerms("the of"), "only stop words -> no terms -> matches nothing");
    }

    @Test
    void englishStopWordsAreBundled() {
        assertEquals(List.of(), Tokenizer.english().tokenize("the and of"));
        assertFalse(Tokenizer.english().tokenize("whale").isEmpty());
    }
}
