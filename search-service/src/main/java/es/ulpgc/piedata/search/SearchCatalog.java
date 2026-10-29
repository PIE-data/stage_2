package es.ulpgc.piedata.search;

import com.google.gson.annotations.SerializedName;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

public final class SearchCatalog {

    private final SearchDatabase database;

    public SearchCatalog(Path dataDir) {
        this.database = new SearchDatabase(dataDir);
    }

    public record BookDetails(
            @SerializedName("book_id") long bookId,
            String title,
            String author,
            String language,
            Integer year
    ) {
    }

    public record YearRange(Integer min, Integer max) {
    }

    public record FilterChoices(
            List<String> languages,
            @SerializedName("year_range") YearRange yearRange
    ) {
    }

    public Optional<BookDetails> book(long bookId) throws SQLException {
        Optional<Optional<BookDetails>> result = database.read(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT book_id, title, author, language, release_year
                    FROM books
                    WHERE book_id = ?
                    """)) {
                statement.setLong(1, bookId);

                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.<BookDetails>empty();
                    }

                    Number year = (Number) rows.getObject("release_year");

                    return Optional.of(new BookDetails(
                            rows.getLong("book_id"),
                            rows.getString("title"),
                            rows.getString("author"),
                            rows.getString("language"),
                            year == null ? null : year.intValue()
                    ));
                }
            }
        });

        return result.orElseGet(Optional::empty);
    }

    public FilterChoices filters() throws SQLException {
        return database.read(connection -> {
            List<String> languages = new ArrayList<>();

            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT DISTINCT language
                         FROM books
                         WHERE language IS NOT NULL AND language <> ''
                         ORDER BY language
                         """)) {
                while (rows.next()) {
                    languages.add(rows.getString("language"));
                }
            }

            Integer min;
            Integer max;

            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT MIN(release_year), MAX(release_year)
                         FROM books
                         """)) {
                rows.next();

                Number first = (Number) rows.getObject(1);
                Number last = (Number) rows.getObject(2);

                min = first == null ? null : first.intValue();
                max = last == null ? null : last.intValue();
            }

            return new FilterChoices(
                    List.copyOf(languages),
                    new YearRange(min, max)
            );
        }).orElseGet(() -> new FilterChoices(
                List.of(),
                new YearRange(null, null)
        ));
    }

    public Map<String, Object> status() throws SQLException {
        return database.read(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM books"
                 )) {
                rows.next();
                long books = rows.getLong(1);

                return Map.<String, Object>of(
                        "datamart", books == 0 ? "empty" : "ready",
                        "books_indexed", books
                );
            }
        }).orElseGet(() -> Map.<String, Object>of(
                "datamart", "empty",
                "books_indexed", 0L
        ));
    }
}