package es.ulpgc.piedata.search;

import es.ulpgc.piedata.common.http.BadRequestException;

import java.util.Locale;
import java.util.Set;

/**
 * Validated parameters of GET /search.
 *
 * Empty optional filters are treated as absent.
 * Query tokenization is performed separately by the Search Service.
 */
public record SearchRequest(
        String query,
        String author,
        String language,
        Integer year,
        int offset,
        int limit
) {
    private static final Set<String> ISO_LANGUAGES =
            Set.of(Locale.getISOLanguages());

    public static SearchRequest parse(
            String query,
            String author,
            String language,
            String year,
            String offset,
            String limit
    ) {
        if (query == null) {
            throw invalid("q", "is required");
        }

        String parsedAuthor = optional(author);
        String parsedLanguage = optional(language);
        String parsedYear = optional(year);

        if (parsedLanguage != null
                && !ISO_LANGUAGES.contains(parsedLanguage)) {
            throw invalid(
                    "language",
                    "must be a lowercase ISO 639-1 code, such as en or es"
            );
        }

        Integer releaseYear = null;
        if (parsedYear != null) {
            if (!parsedYear.matches("[0-9]{4}")) {
                throw invalid("year", "must contain exactly four digits");
            }
            releaseYear = Integer.valueOf(parsedYear);
        }

        int parsedOffset = integer(offset, "offset", 0);
        int parsedLimit = integer(limit, "limit", 20);

        if (parsedLimit < 1 || parsedLimit > 100) {
            throw invalid("limit", "must be between 1 and 100");
        }

        return new SearchRequest(
                query,
                parsedAuthor,
                parsedLanguage,
                releaseYear,
                parsedOffset,
                parsedLimit
        );
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int integer(String raw, String name, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }

        if (!raw.matches("[0-9]+")) {
            throw invalid(name, "must be a non-negative integer");
        }

        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw invalid(name, "exceeds the supported integer range");
        }
    }

    private static BadRequestException invalid(String name, String message) {
        return new BadRequestException(
                "invalid_parameter",
                name + " " + message
        );
    }
}