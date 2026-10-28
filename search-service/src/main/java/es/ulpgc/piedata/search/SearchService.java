package es.ulpgc.piedata.search;

import com.google.gson.annotations.SerializedName;
import es.ulpgc.piedata.common.text.Tokenizer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SearchService {

    private final SearchDatabase database;
    private final Tokenizer tokenizer = Tokenizer.english();

    public SearchService(Path dataDir) {
        this.database = new SearchDatabase(dataDir);
    }

    public record BookHit(
            @SerializedName("book_id") long bookId,
            String title,
            String author,
            String language,
            Integer year,
            double score
    ) {
    }

    public record SearchResponse(
            String query,
            Map<String, Object> filters,
            int total,
            int offset,
            int limit,
            List<BookHit> results
    ) {
    }

    public SearchResponse search(SearchRequest request) throws SQLException {
        List<String> terms = tokenizer.queryTerms(request.query());

        List<BookHit> matches = database.read(connection ->
                terms.isEmpty()
                        ? List.<BookHit>of()
                        : findMatches(connection, request, terms)
        ).orElseGet(List::of);

        int total = matches.size();
        int from = Math.min(request.offset(), total);
        int to = (int) Math.min(
                (long) from + request.limit(),
                total
        );

        return new SearchResponse(
                request.query(),
                filters(request),
                total,
                request.offset(),
                request.limit(),
                List.copyOf(matches.subList(from, to))
        );
    }

    private List<BookHit> findMatches(
            Connection connection,
            SearchRequest request,
            List<String> terms
    ) throws SQLException {

        long numberOfBooks;
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM books")) {
            rows.next();
            numberOfBooks = rows.getLong(1);
        }

        if (numberOfBooks == 0) {
            return List.of();
        }

        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(terms.size(), "?")
        );

        StringBuilder sql = new StringBuilder("""
                SELECT b.book_id, b.title, b.author,
                       b.language, b.release_year,
                       p.term, p.tf, t.df
                FROM postings p
                JOIN books b ON b.book_id = p.book_id
                JOIN terms t ON t.term = p.term
                WHERE p.term IN (
                """);

        sql.append(placeholders).append(")");

        if (request.language() != null) {
            sql.append(" AND b.language = ?");
        }
        if (request.year() != null) {
            sql.append(" AND b.release_year = ?");
        }

        // A fixed summation order also makes score computation reproducible.
        sql.append(" ORDER BY b.book_id, p.term");

        Map<Long, Candidate> candidates = new HashMap<>();

        try (var statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;

            for (String term : terms) {
                statement.setString(parameter++, term);
            }
            if (request.language() != null) {
                statement.setString(parameter++, request.language());
            }
            if (request.year() != null) {
                statement.setInt(parameter++, request.year());
            }

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String author = rows.getString("author");

                    if (!matchesAuthor(author, request.author())) {
                        continue;
                    }

                    long id = rows.getLong("book_id");
                    Candidate candidate = candidates.get(id);

                    if (candidate == null) {
                        Number year = (Number) rows.getObject("release_year");

                        candidate = new Candidate(
                                id,
                                rows.getString("title"),
                                author,
                                rows.getString("language"),
                                year == null ? null : year.intValue()
                        );
                        candidates.put(id, candidate);
                    }

                    long df = rows.getLong("df");
                    long tf = rows.getLong("tf");

                    if (df <= 0 || df > numberOfBooks || tf <= 0) {
                        throw new SQLException(
                                "Inconsistent term statistics in datamart"
                        );
                    }

                    candidate.matchedTerms++;
                    candidate.score += tf * Math.log(
                            (double) numberOfBooks / df
                    );
                }
            }
        }

        List<BookHit> results = new ArrayList<>();

        for (Candidate candidate : candidates.values()) {
            if (candidate.matchedTerms == terms.size()) {
                results.add(candidate.toHit());
            }
        }

        results.sort(
                Comparator.comparingDouble(BookHit::score)
                        .reversed()
                        .thenComparingLong(BookHit::bookId)
        );

        return results;
    }

    private static boolean matchesAuthor(String author, String filter) {
        return filter == null
                || (author != null
                && author.toLowerCase(Locale.ROOT)
                .contains(filter.toLowerCase(Locale.ROOT)));
    }

    private static Map<String, Object> filters(SearchRequest request) {
        Map<String, Object> filters = new LinkedHashMap<>();

        if (request.author() != null) {
            filters.put("author", request.author());
        }
        if (request.language() != null) {
            filters.put("language", request.language());
        }
        if (request.year() != null) {
            filters.put("year", request.year());
        }

        return java.util.Collections.unmodifiableMap(filters);
    }

    private static final class Candidate {
        private final long bookId;
        private final String title;
        private final String author;
        private final String language;
        private final Integer year;
        private int matchedTerms;
        private double score;

        private Candidate(
                long bookId,
                String title,
                String author,
                String language,
                Integer year
        ) {
            this.bookId = bookId;
            this.title = title;
            this.author = author;
            this.language = language;
            this.year = year;
        }

        private BookHit toHit() {
            return new BookHit(
                    bookId, title, author, language, year, score
            );
        }
    }
}