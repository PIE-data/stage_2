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
| `corpus/manifest_*.txt` | feeder (#2), benchmarks (#8) | book ids of the 100 / 1 000 / 10 000 tiers |
| `queries/*.txt` | search tests (#5), benchmarks (#8) | Stage 1 query sets |
| `schemas/*.json` | reference | Stage 1 JSON schemas |

`golden/bodies/` is the only addition: the split body of each golden book, generated once with
the Stage 1 Python splitter (`splitter.split_file`, the same function used to build
`tokens_20.jsonl`) and gzipped. It lets the tokenizer be tested before the Java splitter exists,
and it is the expected output for the Java splitter port.
