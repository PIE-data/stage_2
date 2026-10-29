# Search Service and front-end API use cases

## Purpose

Search is the read-only API over the committed Stage 2 datamart.
It exposes keyword search, book metadata, available filters and service status.

In the deployment architecture, front-end requests reach Search through the
gateway. The examples below use port 7003 to test a Search replica directly.
Gateway deployment and validation belong to issue #7.

## Storage and consistency

Search reads `${data.dir}/datamarts/datamart.db`.

Connections use SQLite read-only mode and `PRAGMA query_only = ON`.
Search does not create the database or modify indexed data.

Database reads for a request use one read transaction, keeping the result
count, ranking inputs and returned page within the same snapshot.
Only committed data is visible.

If the database does not exist, searches return an empty result and status
reports `"datamart": "empty"`. Other database failures are not silently
converted into empty results.

See [DATAMART.md](DATAMART.md) for the shared schema and transaction contract.

## Search semantics

`GET /search` accepts the following parameters:

| Parameter | Meaning | Default |
|---|---|---|
| `q` | Required query, normalized with `Tokenizer.english().queryTerms(q)` | None |
| `author` | Case-insensitive literal substring of the author | No filter |
| `language` | Exact lowercase ISO 639-1 language code | No filter |
| `year` | Four-digit Gutenberg e-book release year | No filter |
| `offset` | Non-negative integer within the supported Java integer range | 0 |
| `limit` | Integer from 1 to 100 | 20 |

Multiple distinct normalized query terms use AND semantics.
A query whose terms are all discarded returns HTTP 200 with no results.
The author, language and year filters can be combined.

The year is the release year of the Gutenberg e-book, not the original
publication year of the work.

Ranking uses:

    score(book) = sum(tf(term, book) * ln(N / df(term)))

`N` is the total number of indexed books and `df` is the number of books
containing the term. Both refer to the full index, before metadata filters.

Results are ordered by descending score, then ascending book ID.
`total` counts all matches before pagination; `results` contains only the
requested page.

Invalid parameters return HTTP 400 with an error code and message.
Search parameter validation uses `BadRequestException`.

## Endpoints

| Method and route | Purpose | Success |
|---|---|---|
| `GET /search` | Keyword search with optional filters and pagination | 200 |
| `GET /books/{book_id}` | Metadata for one indexed book | 200 |
| `GET /search/filters` | Available languages and release-year range | 200 |
| `GET /status` | Service configuration, uptime and datamart readiness | 200 |

Unknown book IDs return HTTP 404.
Malformed book IDs return HTTP 400.

Responses use JSON with UTF-8 encoding.
Errors have the form:

```json
{"error":"invalid_parameter","message":"q is required"}
```

CORS is enabled with any origin allowed.

## Front-end API use cases

### 1. Populate filter controls

Request:

```http
GET /search/filters
```

Example from the tier-1000 dataset:

```json
{
  "languages": ["en"],
  "year_range": {"min": 1994, "max": 2023}
}
```

Use the languages to populate a language selector and the range to describe
available release years. With no indexed data, the language list is empty
and the year bounds are null.

### 2. Submit a keyword query

Request:

```http
GET /search?q=sea&offset=0&limit=3
```

The response contains:

- `query`: the query string;
- `filters`: the active metadata filters;
- `total`: the number of matching books;
- `offset` and `limit`: pagination settings;
- `results`: book ID, title, author, language, release year and score.

On the tested tier-1000 datamart, `sea` matched 823 books.

The front-end displays results in the returned order and handles nullable
metadata fields without displaying the literal text "null".

### 3. Search for multiple terms

Request:

```http
GET /search?q=sea%20ship&limit=3
```

Only books containing both normalized terms match.
On the tested tier-1000 datamart, this query matched 567 books.

Query values must be URL-encoded.

### 4. Combine metadata filters

Request:

```http
GET /search?q=sea%20ship&author=Stacpoole&language=en&year=2017
```

All supplied filters must match. Author matching ignores case and uses a
literal substring.

When a query or filter changes, reset the pagination offset to zero.

### 5. Navigate result pages

For a page size of 20:

```http
GET /search?q=sea&offset=0&limit=20
GET /search?q=sea&offset=20&limit=20
```

Preserve the query and filters when changing pages.
Disable the next-page control when `offset + results.length >= total`.

An offset beyond the available results returns an empty page while
preserving the total match count.

Each request has a consistent snapshot, but separate page requests can
observe different committed index versions if indexing continues.

### 6. Open book details

Request:

```http
GET /books/18175
```

Example from the golden dataset:

```json
{
  "book_id": 18175,
  "title": "Yorksher Puddin'",
  "author": "John Hartley",
  "language": "en",
  "year": 2006
}
```

This endpoint returns metadata. It does not return the full book text.
Handle HTTP 404 by displaying that the book is unavailable.

### 7. Handle empty results and invalid input

A valid search with no matches returns HTTP 200, `total: 0` and `results: []`.

A request without `q`, or with an invalid pagination value such as `limit=0`,
returns HTTP 400. The front-end can display the returned validation message.

An empty result is not a server failure. To distinguish an unavailable
datamart from a query with no matches, inspect `/status`.

## Configuration and replicas

Default configuration:

| Key | Default |
|---|---|
| `service.name` | `search` |
| `server.port` | `7003` |
| `server.threads.min` | `8` |
| `server.threads.max` | `250` |
| `data.dir` | `./data` |

Configuration precedence, from lowest to highest, is:
bundled defaults, external configuration file, environment variables,
JVM properties.

The Jetty thread limits apply to each process independently.
They describe server thread-pool limits, not a measured concurrent-user capacity.

Build from the repository root:

```bash
mvn verify
```

Start a replica against an existing workspace:

```bash
java "-Ddata.dir=/absolute/path/to/s2-tier1000" "-Dserver.port=7003" -jar search-service/target/search-service.jar
```

Start a second replica in another terminal, using the same workspace:

```bash
java "-Ddata.dir=/absolute/path/to/s2-tier1000" "-Dserver.port=7013" -jar search-service/target/search-service.jar
```

On Windows, replace the example data path with the full Windows path.
Keep the entire `-Ddata.dir=...` argument quoted when the path contains spaces.

To override the thread pool, add JVM arguments before `-jar`, for example:

```text
-Dserver.threads.min=8 -Dserver.threads.max=64
```

Each replica opens its own read-only database connections.

## Validation and performance

Automated Search tests cover request validation, read-only access, search
semantics, ranking, filters, pagination, HTTP responses, CORS and transaction
consistency.

The golden dataset was indexed and its canonical hash verified.
Manual HTTP checks confirmed filtering, pagination and identical responses
from two Search replicas against the same dataset.

The tier-1000 datamart was also queried through HTTP.
These checks do not replace the full deployment and failure-recovery
validation required by issue #7.

JMH measurements for single-term, multi-term and filtered searches are
documented in [SEARCH_BENCHMARK.md](SEARCH_BENCHMARK.md).
They measure the Java search operation and exclude HTTP and gateway overhead.