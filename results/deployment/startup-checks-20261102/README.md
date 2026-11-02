# Deployment startup checks — 2026-11-02

Environment: Ubuntu 24.04 on WSL 2, Java 17.
These are manual integration checks of the deployment scripts.

## Duplicate startup

With two Search replicas and the gateway already running, a second
run-all invocation returned exit code 1.
The original PIDs remained unchanged and a gateway search still returned
HTTP 200 with total 823.

## Occupied port

A temporary Python HTTP server occupied port 7013.
run-all returned exit code 1 before starting any managed service.
The process registry remained empty and the temporary HTTP server
continued to return HTTP 200.

## Failure after partial startup

A temporary Java wrapper ran the first Search replica normally and
intentionally exited with code 42 for the second replica (port 7013).

The wrapper required REAL_JAVA_FOR_TEST to be exported in the invoking
terminal. An initial attempt without that variable failed at the first
replica and was not counted as the partial-startup validation.

In the successful test:
- Search replica 1 became ready on port 7003.
- Search replica 2 exited intentionally.
- run-all returned exit code 1 and stopped replica 1.
- The process registry contained [].
- No listeners remained on ports 7003, 7013 or 8080.

The accompanying search-1.log and search-2.log are from this successful
failure-injection test. Exit code, registry and port checks were observed
in the terminal; they are not asserted to be contained in these logs.

These checks do not validate the complete ingestion/indexing pipeline
or message recovery after an abrupt replica failure.
