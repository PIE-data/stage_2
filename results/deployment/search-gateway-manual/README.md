# Manual Search gateway validation

Date: 2026-10-29
Environment: Ubuntu 24.04 on WSL 2, Java 17, nginx 1.24.0.

## Setup

- Stage 2 datamart containing 1,000 books.
- Two Search replicas on ports 7003 and 7013.
- nginx gateway on port 8080.
- Round-robin balancing and retry on upstream connection errors/timeouts.
- Configuration: nginx.conf.
- Recorded requests: access.log.

## Observations

1. Both replicas reported a ready datamart with 1,000 books.
2. Requests through the gateway reached both replicas.
3. After stopping replica 7013 with Ctrl+C, six consecutive searches
   returned HTTP 200, total 823, and book 9600 as the first result.
4. The first search after the stop recorded upstream statuses 502, 200:
   nginx retried the failed request on replica 7003 successfully.
5. After restarting replica 7013, it received gateway requests again.

The shared status endpoint was corrected to report app.port() instead of
ctx.port(), so its port field identifies the actual listener behind a proxy.

## Scope

This validates Search balancing and recovery after a graceful replica stop.
It does not validate an abrupt process kill, ingestion/indexing message
redelivery, or the complete tier-100 pipeline required by issue #7.

The configuration is a standalone nginx configuration. Relative paths are
resolved using the prefix supplied with nginx -p.
