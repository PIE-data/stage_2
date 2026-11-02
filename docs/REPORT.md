# Coordination and Event-Driven Architecture

## 1. Architecture and Event Topology

Stage 2 transitions the data pipeline from Stage 1's sequential batch execution into an autonomous event-driven machine. Instead of having a central controller making direct HTTP calls between services, our services coordinate asynchronously through an embedded **ActiveMQ** message broker running on `tcp://localhost:61616`.

This design gives us several practical benefits:
- **Decoupled services**: A service just publishes an event when its work is done without caring which replica or process will pick it up.
- **Easy load balancing**: If we start two or three replicas of Ingestion or Indexing, they automatically share the workload simply by listening to the same queue.
- **Buffering**: If Indexing is busy or slow with a large book, Ingestion doesn't get blocked; messages simply wait safely in the broker queue.

### Protocol and Message Rules
All messages follow the rules defined in `docs/EVENTS.md`:
- **Protocol**: We use OpenWire over TCP. On the client side, we wrap the URL with `failover:(tcp://localhost:61616)` so that if the broker restarts, consumers and producers wait and reconnect automatically instead of crashing.
- **Message format**: Messages are persistent JMS `TextMessage`s containing a single UTF-8 JSON object. If a message arrives with unknown extra fields, consumers ignore them so that future updates don't break existing services.
- **JMS headers**: In addition to the JSON body, we set the `book_id` (long) as a JMS property on every message. This makes it easy to log, inspect, or filter messages in the broker without having to parse the JSON body every time.
- **Prefetch = 1**: ActiveMQ is configured with a prefetch limit of 1. By default, ActiveMQ tries to give batches of messages to the first consumer that connects. Setting prefetch to 1 forces it to deliver messages one by one, keeping all replicas equally busy.

### Destination Mapping

| Name | Type | Producer | Consumers | What it means |
|---|---|---|---|---|
| `ingest.requests` | Queue | Feeder, `POST /ingest/{id}` | Ingestion replicas | "Please download and split this book into the datalake." |
| `books.downloaded` | Queue | Ingestion | Indexing replicas | "The book files and receipt are ready on disk." |
| `books.indexed` | Topic | Indexing | Feeder, monitoring | "This book is committed in SQLite and ready to search." |
| `ActiveMQ.DLQ` | Queue | ActiveMQ broker | Operators, checks | Holds messages that failed all 5 retries or were malformed. |

## 2. Transacted Acknowledgements and Atomic Hand-off

One of the trickiest problems in message-driven systems is what happens if a worker crashes halfway through:
- If we acknowledge the message as soon as we receive it and the process dies while writing files, the message is gone from the broker and that book is lost forever.
- If we don't acknowledge until after writing, but the process dies right before sending the next event, the work is done on disk but the next service is never notified.

To solve this without needing complex distributed transactions (like 2PC or XA), we use **JMS transacted sessions** (`Session.SESSION_TRANSACTED`) in every consumer.

### The Processing Cycle
For every message received, the consumer follows this sequence:
```
receive(message) 
  → do the work 
  → commit to storage (save files to datalake or write to SQLite)
  → send next message in the same JMS session
  → session.commit()
```

By doing this:
1. **Atomic hand-off**: The acknowledgement of the incoming message and the publishing of the outgoing message happen in the exact same transaction. The broker will not publish the outgoing message to downstream consumers unless `session.commit()` succeeds.
2. **Safe rollbacks**: If anything throws an unhandled exception (for instance, an I/O error, a database timeout, or malformed data), we immediately execute `session.rollback()`. The broker puts the input message back into the queue for redelivery, and any uncommitted outgoing messages are discarded.
3. **Crash safety**: If the process gets killed (e.g. `kill -9` or power loss) before `session.commit()`, ActiveMQ detects that the connection dropped and simply delivers the unacknowledged message to another replica.

## 3. Retries, Errors, and the Dead Letter Queue (DLQ)

Things can go wrong in distributed systems, but not all errors are the same. We classify problems into three categories and handle each one differently:

### A. Transient Errors (Network hiccups, SQLite database locks, temporary I/O failures)
Sometimes an error is just temporary—for example, if another replica is writing to SQLite and we hit a `SQLITE_BUSY` lock timeout.
- **What the consumer does**: Calls `session.rollback()`.
- **How ActiveMQ reacts**: The broker holds the message and applies an exponential backoff retry policy:
  - Up to **5 redeliveries** (6 attempts in total).
  - Delay doubles each time: **1s, 2s, 4s, 8s, 16s**.
- If the problem fixes itself on the next attempt, the message processes normally.

### B. Poison Messages (Corrupted JSON, unknown event name, invalid book ID)
If someone pushes a broken message (like `{"event": "UnknownEvent"}` or negative ID `book_id: -1`), retrying it 100 times will never fix it.
- **What the consumer does**: Our `EventParser` validates the payload. If it's malformed or invalid, it throws an `IllegalArgumentException`, causing a `session.rollback()`.
- **How ActiveMQ reacts**: After the 5 redeliveries are exhausted, the client marks it as poisoned. The broker moves it into the quarantine queue: **`ActiveMQ.DLQ`**.
- This protects workers from getting stuck in an infinite retry loop on bad data.

### C. Permanent Domain Errors (404 on Gutenberg, missing header/body markers)
Some books simply do not exist or cannot be parsed because Gutenberg removed them or their format is broken.
- **What the consumer does**: Instead of rolling back (which would waste 5 retries), Ingestion records the failure by appending `<book_id>\tNOT_FOUND|NO_MARKERS\t<timestamp>` to `control/failed_books.txt`, and then calls `session.commit()`.
- **Result**: The message is cleared from the queue, will not be retried, and is reported as `failed` in status queries.

## 4. At-Least-Once Delivery and Idempotency

In distributed systems, guaranteeing that a message is delivered *exactly once* without massive performance penalties is practically impossible. If a worker finishes its job but crashes before the broker receives the commit, that message will be delivered again.

Because our delivery guarantee is **at least once**, we design every consumer to be **idempotent**: running the same message twice produces the exact same result as running it once, without corrupting data.

Here is how each component stays idempotent:

### Ingestion Idempotency
- When an Ingestion replica receives `IngestRequested`, it uses our `IdempotentConsumer` helper to check if the receipt `control/ingestion/hash/<id>.json` already exists.
- If the receipt is already there, it skips downloading and simply re-emits `BookDownloaded`. This is harmless and ensures Indexing gets notified even if a previous announcement was lost.
- To prevent two replicas from downloading the same book at the exact same time, the first replica acquires an OS file lock on `control/claims/<id>.lock` (`FileChannel.lock`). The second replica blocks on the lock, and once acquired, finds the receipt already completed and skips.

### Indexing Idempotency
- When Indexing receives `BookDownloaded` for a book that is already in `datamart.db`, it does not append duplicate rows.
- In a single SQLite transaction (`BEGIN IMMEDIATE`), it first decrements the term frequencies (`df = df - 1`), deletes the old postings for that `book_id`, and then inserts the new ones.
- Re-indexing a book 1 time or 10 times results in the exact same database state.

### Feeder Idempotency
- The Feeder tracks finished books using a thread-safe set of IDs (`ConcurrentHashMap.newKeySet()`).
- If it receives the same `BookIndexed` topic notification twice, it recognizes the ID and does not double-count its progress.

## 5. Broker Durability and Startup Recovery

While transacted sessions protect consumers during execution, two specific recovery scenarios require dedicated handling:

### Producer Startup Rule ("Work committed but event not published")
For consumers, the output message is published within the same transaction that acknowledges the input, making event loss impossible.
However, standalone producers (like `Feeder` or `POST /ingest/{id}`) do not run inside a consumer transaction:
- If a producer finishes saving work to disk but crashes before sending the event, it performs a reconciliation check when it boots back up.
- For example, Ingestion scans its disk receipts (`control/ingestion/hash/`) and Feeder checks `GET /ingest/list`, republishing any pending events.
- Because consumers are already idempotent, republishing these events at startup is safe and heals the pipeline.

### Broker Outage and KahaDB
If the ActiveMQ process itself crashes or the server is rebooted:
- **Persistent storage**: Unacknowledged messages are stored in KahaDB transaction logs under `./data/broker`, so no queued books are lost.
- **Failover reconnection**: Services do not crash; their `failover:` transport keeps retrying in the background and resumes the moment the broker is restarted.

## 6. Feeder Design and Progress Monitoring

The Feeder is the dispatcher that initiates ingestion runs and monitors their progress from start to finish.

### Core Workflow
1. **Manifest Reading**: Accepts a Stage 1 corpus manifest (`spec/corpus/manifest_{100,1000,10000}.txt` or a custom file) and parses the Gutenberg IDs.
2. **Pre-Filtering**: Queries `GET /ingest/list` on the Ingestion Service (falling back to local receipts in `data/control/ingestion/hash/` if Ingestion is not running yet). It filters out books already downloaded, avoiding redundant network requests.
3. **Publishing**: For the remaining books (up to the requested `count`), it sends `IngestRequested` events into the `ingest.requests` queue.
4. **Real-time Tracking**: It subscribes to the `books.indexed` topic using a non-durable consumer. Whenever Indexing finishes a book, Feeder records the ID and increments its internal progress counter.

### Dual Operating Modes
The Feeder can be run in two ways depending on the use case:
- **CLI Mode (for automated scripts and benchmarks)**:
  ```bash
  java -jar feeder/target/feeder.jar --manifest spec/corpus/manifest_100.txt --count 20
  ```
  Runs directly in the terminal, feeds the specified books, prints the JSON summary, and exits.
- **HTTP Server Mode (daemon on port 7004)**:
  ```bash
  java -jar feeder/target/feeder.jar
  ```
  Exposes the following REST endpoints:
  - `POST /feeder/run?manifest=100&count=20`: Triggers an ingestion run asynchronously.
  - `GET /feeder/status`: Returns live progress metrics:
    ```json
    {
      "requested": 20,
      "downloaded": 20,
      "indexed": 18,
      "failed": 0
    }
    ```
  - `GET /status`: Standard service health check reporting uptime and port.
