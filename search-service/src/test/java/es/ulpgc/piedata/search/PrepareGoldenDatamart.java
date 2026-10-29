package es.ulpgc.piedata.search;

import es.ulpgc.piedata.common.datamart.BookIndexer;
import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.datalake.Receipt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class PrepareGoldenDatamart {

    private PrepareGoldenDatamart() {
    }

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        Path golden = root.resolve("spec/golden");
        Path dataDir = root.resolve(
                "search-service/target/golden-demo/data"
        );
        Path databaseFile = dataDir.resolve("datamarts/datamart.db");

        if (Files.exists(databaseFile)) {
            throw new IllegalStateException(
                    "Demo database already exists: " + databaseFile
                            + ". Reuse it or choose another output directory."
            );
        }

        var ids = Files.readAllLines(
                        golden.resolve("manifest_20.txt"),
                        StandardCharsets.UTF_8
                ).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Long::parseLong)
                .sorted()
                .toList();

        if (ids.size() != 20 || ids.stream().distinct().count() != 20) {
            throw new IllegalStateException(
                    "Expected exactly 20 distinct golden book IDs"
            );
        }

        BookIndexer indexer = BookIndexer.standard();

        try (Datamart datamart = Datamart.open(databaseFile)) {
            for (long id : ids) {
                String padded = String.format(Locale.ROOT, "%06d", id);
                String prefix = "datalake/"
                        + padded.substring(0, 2) + "/"
                        + padded.substring(2, 4) + "/" + id;

                String headerPath = prefix + ".header.txt";
                String bodyPath = prefix + ".body.txt";
                String ingestedAt = "2026-10-01T00:00:00Z";

                String header = Files.readString(
                        golden.resolve("headers/" + id + ".header.txt"),
                        StandardCharsets.UTF_8
                );

                byte[] body;
                try (var input = new GZIPInputStream(
                        Files.newInputStream(
                                golden.resolve("bodies/" + id + ".body.txt.gz")
                        )
                )) {
                    body = input.readAllBytes();
                }

                Files.createDirectories(
                        dataDir.resolve(headerPath).getParent()
                );
                Files.writeString(
                        dataDir.resolve(headerPath),
                        header,
                        StandardCharsets.UTF_8
                );
                Files.write(dataDir.resolve(bodyPath), body);

                Receipt receipt = new Receipt(
                        id, headerPath, bodyPath, ingestedAt
                );
                Path receiptFile = dataDir.resolve(
                        "control/ingestion/hash/" + id + ".json"
                );

                Files.createDirectories(receiptFile.getParent());
                Files.writeString(
                        receiptFile,
                        receipt.toJsonLine(),
                        StandardCharsets.UTF_8
                );

                datamart.write(indexer.index(
                        id, header, body,
                        headerPath, bodyPath, ingestedAt
                ));

                System.out.println("Indexed book " + id);
            }

            String expected = Files.readString(
                    golden.resolve("expected.sha256"),
                    StandardCharsets.UTF_8
            ).trim().split("\\s+")[0];

            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(datamart.exportCanonical())
            );

            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException(
                        "Canonical hash mismatch: expected "
                                + expected + ", actual " + actual
                );
            }

            if (datamart.stats().books() != 20
                    || datamart.inconsistentTerms() != 0) {
                throw new IllegalStateException(
                        "Unexpected book count or inconsistent term statistics"
                );
            }

            System.out.println("Golden canonical hash verified: " + actual);
            System.out.println("Books indexed: " + datamart.stats().books());
        }

        System.out.println("Demo data directory: " + dataDir);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null) {
            if (Files.isRegularFile(
                    current.resolve("spec/golden/manifest_20.txt"))
                    && Files.isDirectory(
                    current.resolve("search-service"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException(
                "Cannot locate the stage_2 project root"
        );
    }
}