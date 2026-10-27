# spec/ — frozen inputs and oracles from Stage 1

Copied unchanged from [PIE-data/stage_1 `spec/`](https://github.com/PIE-data/stage_1/tree/main/spec)
(SPEC version in `SPEC_VERSION`). The rules they implement are in the Stage 1
[SPEC.md](https://github.com/PIE-data/stage_1/blob/main/docs/SPEC.md).
**Do not edit these files**: they are the reference that proves the Java services behave exactly
like Stage 1. Changing one is a specification change, not a fix.

| File | Used by | Purpose |
|---|---|---|
| `stopwords_en.txt` | tokenizer (`common`) | stop-word list, SPEC §3.3 — bundled into the `common` jar |
| `language_map.txt` | header parser (`common`, #5) | language name → ISO 639-1, SPEC §5.2 — bundled into the `common` jar |
| `golden/<id>.txt`, `manifest_20.txt` | tests | the 20 golden books, raw Gutenberg text |
| `golden/tokens_20.jsonl` | tokenizer test | expected token counts and SHA-256 per golden book, SPEC §3.4 |
| `golden/expected.sha256` | indexing test (#4) | SHA-256 of the canonical index export of the golden books, SPEC §7 |
| `golden/bodies/<id>.body.txt.gz` | tokenizer test, splitter test (#3) | body of each golden book as produced by the Stage 1 splitter (see below) |
| `golden/headers/<id>.header.txt` | header parser test, splitter test (#3) | header of each golden book as produced by the Stage 1 splitter |
| `golden/metadata_20.jsonl` | header parser test (#4) | title, author, language, release date of each golden book from the Stage 1 parser |
| `golden/metadata_headers.json` | header parser test (#4) | the Stage 1 unit cases of the metadata parser (`src/python/datamart/fixtures`) |
| `corpus/manifest_*.txt` | feeder (#2), benchmarks (#8) | book ids of the 100 / 1 000 / 10 000 tiers |
| `queries/*.txt` | search tests (#5), benchmarks (#8) | Stage 1 query sets |
| `schemas/*.json` | reference | Stage 1 JSON schemas |

Additions (generated once from the Stage 1 Python code, never edited by hand):

- `golden/bodies/` and `golden/headers/`: the split body (gzipped) and header of each golden
  book, from `splitter.split_file`, the function used to build `tokens_20.jsonl`. They let the
  tokenizer and the header parser be tested before the Java splitter exists, and they are the
  expected output of the Java splitter port.
- `golden/metadata_20.jsonl`: `metadata.parse_header` applied to `golden/headers/`.
- `golden/metadata_headers.json`: copy of the Stage 1 parser unit fixture.
