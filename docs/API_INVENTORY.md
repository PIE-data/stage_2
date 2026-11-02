# API Inventory — Stage 2

This document gives a simple overview of the HTTP endpoints used in Stage 2.

It answers three basic questions for each endpoint:

- Who provides it?
- Who uses it?
- What is it used for?

HTTP endpoints are grouped into:

- front-end endpoints,
- internal endpoints,
- administrative and health endpoints.

Communication through ActiveMQ is not HTTP, so it is documented separately in [EVENTS.md](EVENTS.md).

## 1. Front-end API

These endpoints are provided by the Search Service.

They are the endpoints that a front-end application, or a user testing the API, would use to search for books and display information.

| Method | Route              | Provider       | Consumer                | Purpose                                                                                                            |
| ------ | ------------------ | -------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------ |
| GET    | `/search`          | Search Service | Front-end / HTTP client | Search books using words entered by the user. Optional filters such as author, language and year can also be used. |
| GET    | `/search/filters`  | Search Service | Front-end / HTTP client | Get the languages and year range currently available in the indexed books.                                         |
| GET    | `/books/{book_id}` | Search Service | Front-end / HTTP client | Get information about one specific indexed book.                                                                   |

The Search Service uses port `7003` by default.

Example:

`GET /search?q=sea&language=en`

means:

“Find indexed books containing the query `sea` and keep only English-language results.”

In the final deployment, the front-end may access Search through the gateway instead of calling port `7003` directly.

## 2. Internal communication

The main workflow between Ingestion and Indexing does not use direct HTTP calls.

Instead, the services communicate through ActiveMQ events.

The main event flow is:

`Feeder → ingest.requests → Ingestion → books.downloaded → Indexing → books.indexed`

This means:

- `ingest.requests` tells Ingestion that a book should be downloaded.
- `books.downloaded` tells Indexing that the book is completely stored in the datalake and is ready to be indexed.
- `books.indexed` announces that the book has been indexed and is now searchable.

The full event rules, including message payloads, retries, acknowledgements and duplicate handling, are documented in [EVENTS.md](EVENTS.md).

The internal HTTP section will be updated if additional service-to-service HTTP endpoints are implemented.

## 3. Administrative and health API

These endpoints are not used by the normal search front-end.

They are mainly used by developers, scripts, benchmarks, monitoring or validation.

| Method | Route                     | Provider          | Consumer                         | Purpose                                                                                          |
| ------ | ------------------------- | ----------------- | -------------------------------- | ------------------------------------------------------------------------------------------------ |
| POST   | `/index/update/{book_id}` | Indexing Service  | Scripts / developer / validation | Index or re-index one book that has already been ingested.                                       |
| POST   | `/index/rebuild`          | Indexing Service  | Scripts / developer / validation | Rebuild the whole datamart from the available ingested books.                                    |
| GET    | `/index/status`           | Indexing Service  | Monitoring / scripts             | Check how many books, terms and postings are currently indexed and whether a rebuild is running. |
| GET    | `/status`                 | Search Service    | Monitoring / scripts             | Check whether Search is running and whether its datamart is ready.                               |
| GET    | `/status`                 | Indexing Service  | Monitoring / scripts             | Check whether Indexing is running.                                                               |
| GET    | `/status`                 | Ingestion Service | Monitoring / scripts             | Check whether Ingestion is running.                                                              |
| GET    | `/status`                 | Feeder            | Monitoring / scripts             | Check whether the Feeder is running.                                                             |

Default ports:

| Service           |   Port |
| ----------------- | -----: |
| Ingestion Service | `7001` |
| Indexing Service  | `7002` |
| Search Service    | `7003` |
| Feeder            | `7004` |

## 4. How to think about the API

The API is the agreed way to communicate with a service.

An endpoint is one specific operation inside that API.

For example:

`GET /search`

means:

“Search for books.”

`GET /books/1342`

means:

“Give me information about book 1342.”

`POST /index/rebuild`

means:

“Rebuild the index.”

`GET /status`

means:

“Tell me whether this service is running.”

The detailed rules for every HTTP endpoint, including parameters, responses, errors and validation, are defined in `api/openapi.yaml`.
