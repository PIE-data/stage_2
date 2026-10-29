package es.ulpgc.piedata.search;

import es.ulpgc.piedata.common.http.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchRequestTest {

    @Test
    void usesDefaultPagination() {
        SearchRequest request = parse("adventure", null, null, null, null, null);

        assertAll(
                () -> assertEquals("adventure", request.query()),
                () -> assertNull(request.author()),
                () -> assertNull(request.language()),
                () -> assertNull(request.year()),
                () -> assertEquals(0, request.offset()),
                () -> assertEquals(20, request.limit())
        );
    }

    @Test
    void acceptsCombinedFiltersAndPagination() {
        SearchRequest request =
                parse("sea adventure", " Austen ", "en", "1998", "10", "100");

        assertAll(
                () -> assertEquals("Austen", request.author()),
                () -> assertEquals("en", request.language()),
                () -> assertEquals(Integer.valueOf(1998), request.year()),
                () -> assertEquals(10, request.offset()),
                () -> assertEquals(100, request.limit())
        );
    }

    @Test
    void requiresQueryButAllowsAnEmptyQuery() {
        assertThrows(
                BadRequestException.class,
                () -> parse(null, null, null, null, null, null)
        );

        assertEquals(
                "",
                parse("", null, null, null, null, null).query()
        );
    }

    @Test
    void treatsEmptyOptionalFiltersAsAbsent() {
        SearchRequest request = parse("sea", " ", "", " ", null, null);

        assertAll(
                () -> assertNull(request.author()),
                () -> assertNull(request.language()),
                () -> assertNull(request.year())
        );
    }

    @Test
    void rejectsInvalidLanguages() {
        for (String language : new String[]{"EN", "english", "zz", "e"}) {
            assertThrows(
                    BadRequestException.class,
                    () -> parse("sea", null, language, null, null, null),
                    language
            );
        }
    }

    @Test
    void rejectsMalformedYears() {
        for (String year : new String[]{"98", "199", "19980", "19ab", "-100"}) {
            assertThrows(
                    BadRequestException.class,
                    () -> parse("sea", null, null, year, null, null),
                    year
            );
        }
    }

    @Test
    void rejectsInvalidOffsets() {
        for (String offset : new String[]{"-1", "abc", "1.5", "", "2147483648"}) {
            assertThrows(
                    BadRequestException.class,
                    () -> parse("sea", null, null, null, offset, null),
                    offset
            );
        }
    }

    @Test
    void rejectsInvalidLimits() {
        for (String limit : new String[]{"0", "101", "-1", "abc", "", "2147483648"}) {
            assertThrows(
                    BadRequestException.class,
                    () -> parse("sea", null, null, null, null, limit),
                    limit
            );
        }
    }

    private static SearchRequest parse(
            String query,
            String author,
            String language,
            String year,
            String offset,
            String limit
    ) {
        return SearchRequest.parse(query, author, language, year, offset, limit);
    }
}