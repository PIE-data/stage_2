#!/usr/bin/env bash

set -u

BROKER_URL="${BROKER_URL:-http://localhost:7000}"
INGESTION_URL="${INGESTION_URL:-http://localhost:7001}"
INDEXING_URL="${INDEXING_URL:-http://localhost:7002}"
SEARCH_URL="${SEARCH_URL:-http://localhost:7003}"
FEEDER_URL="${FEEDER_URL:-http://localhost:7004}"
GATEWAY_URL="${GATEWAY_URL:-}"
BOOK_ID="${BOOK_ID:-1342}"
RUN_MUTATING="${RUN_MUTATING:-1}"
RUN_EVENT_CHECKS="${RUN_EVENT_CHECKS:-1}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"

PASS=0
FAIL=0
SKIP=0

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

pass() { echo "PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL  $1"; FAIL=$((FAIL + 1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP + 1)); }

request_status() {
  local method="$1"
  local url="$2"
  local actual
  actual="$(curl --silent --show-error --output "$BODY_FILE" --write-out "%{http_code}" --request "$method" "$url" 2>/dev/null)" || actual="000"
  printf "%s" "$actual"
}

check_json_field() {
  local name="$1" method="$2" url="$3" expected_status="$4" expected_text="$5"
  local actual
  actual="$(request_status "$method" "$url")"

  if [[ "$actual" != "$expected_status" ]]; then
    fail "$name -> expected HTTP $expected_status, got $actual"
    [[ -s "$BODY_FILE" ]] && echo "      Response: $(cat "$BODY_FILE")"
    return
  fi

  if grep -Fq "$expected_text" "$BODY_FILE"; then
    pass "$name -> HTTP $actual"
  else
    fail "$name -> HTTP $actual but expected response content was missing"
    echo "      Expected: $expected_text"
    echo "      Response: $(cat "$BODY_FILE")"
  fi
}

check_json_field_any_status() {
  local name="$1" method="$2" url="$3" expected_statuses="$4" expected_text="$5"
  local actual
  actual="$(request_status "$method" "$url")"

  if [[ ! "|$expected_statuses|" =~ \|$actual\| ]]; then
    fail "$name -> expected one of [$expected_statuses], got $actual"
    [[ -s "$BODY_FILE" ]] && echo "      Response: $(cat "$BODY_FILE")"
    return
  fi

  if grep -Fq "$expected_text" "$BODY_FILE"; then
    pass "$name -> HTTP $actual"
  else
    fail "$name -> HTTP $actual but expected response content was missing"
    echo "      Response: $(cat "$BODY_FILE")"
  fi
}

wait_for_ingestion() {
  local id="$1" elapsed=0
  while [[ "$elapsed" -lt "$WAIT_SECONDS" ]]; do
    local actual
    actual="$(request_status GET "$INGESTION_URL/ingest/status/$id")"
    if [[ "$actual" == "200" ]]; then
      if grep -Fq '"status":"downloaded"' "$BODY_FILE"; then
        pass "Book $id reached ingestion status downloaded"
        return 0
      fi
      if grep -Fq '"status":"failed"' "$BODY_FILE"; then
        fail "Book $id ingestion failed -> $(cat "$BODY_FILE")"
        return 1
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  fail "Book $id did not finish ingestion within ${WAIT_SECONDS}s"
  return 1
}

echo
echo "========================================"
echo " PIE-data Stage 2 API checks"
echo "========================================"
echo

echo "1. Health checks"
echo "----------------"
check_json_field "Broker /status" GET "$BROKER_URL/status" 200 '"service":"broker"'
check_json_field "Ingestion /status" GET "$INGESTION_URL/status" 200 '"service":"ingestion"'
check_json_field "Indexing /status" GET "$INDEXING_URL/status" 200 '"service":"indexing"'
check_json_field "Search /status" GET "$SEARCH_URL/status" 200 '"service":"search"'
check_json_field "Feeder /status" GET "$FEEDER_URL/status" 200 '"service":"feeder"'

echo
echo "2. Broker monitoring"
echo "--------------------"
check_json_field "Broker queue stats" GET "$BROKER_URL/broker/stats" 200 '"ingest.requests"'

echo
echo "3. Ingestion API"
echo "----------------"
check_json_field "Ingest list" GET "$INGESTION_URL/ingest/list" 200 '['
check_json_field "Ingest status" GET "$INGESTION_URL/ingest/status/$BOOK_ID" 200 '"bookId"'
check_json_field "Invalid ingest book id" GET "$INGESTION_URL/ingest/status/abc" 400 '"error":"invalid_book_id"'

echo
echo "4. Search API"
echo "-------------"
check_json_field "Basic search" GET "$SEARCH_URL/search?q=sea" 200 '"results"'
check_json_field "Search with filters" GET "$SEARCH_URL/search?q=sea&language=en&offset=0&limit=20" 200 '"query":"sea"'
check_json_field "Available search filters" GET "$SEARCH_URL/search/filters" 200 '"languages"'
check_json_field "Missing q" GET "$SEARCH_URL/search" 400 '"error":"invalid_parameter"'
check_json_field "Invalid limit" GET "$SEARCH_URL/search?q=sea&limit=500" 400 '"error":"invalid_parameter"'
check_json_field "Invalid language" GET "$SEARCH_URL/search?q=sea&language=english" 400 '"error":"invalid_parameter"'
check_json_field "Invalid year" GET "$SEARCH_URL/search?q=sea&year=20" 400 '"error":"invalid_parameter"'
check_json_field "Negative offset" GET "$SEARCH_URL/search?q=sea&offset=-1" 400 '"error":"invalid_parameter"'
check_json_field "Duplicate q" GET "$SEARCH_URL/search?q=sea&q=ship" 400 '"error":"invalid_parameter"'
check_json_field "Invalid Search book id" GET "$SEARCH_URL/books/abc" 400 '"error":"invalid_book_id"'

echo
echo "5. Indexing and Feeder API"
echo "--------------------------"
check_json_field "Index status" GET "$INDEXING_URL/index/status" 200 '"books_indexed"'
check_json_field "Invalid index update id" POST "$INDEXING_URL/index/update/0" 400 '"error":"invalid_book_id"'
check_json_field "Feeder status" GET "$FEEDER_URL/feeder/status" 200 '"requested"'

echo
echo "6. State-changing endpoint checks"
echo "---------------------------------"
if [[ "$RUN_MUTATING" == "1" ]]; then
  check_json_field_any_status "Request ingestion of book $BOOK_ID" POST "$INGESTION_URL/ingest/$BOOK_ID" "200|202" "\"book_id\":$BOOK_ID"
  if wait_for_ingestion "$BOOK_ID"; then
    check_json_field "Index or re-index book $BOOK_ID" POST "$INDEXING_URL/index/update/$BOOK_ID" 200 '"index":"updated"'
    check_json_field "Book details after indexing" GET "$SEARCH_URL/books/$BOOK_ID" 200 "\"book_id\":$BOOK_ID"
  fi
  check_json_field "Rebuild datamart" POST "$INDEXING_URL/index/rebuild" 200 '"books_processed"'
  check_json_field "Run Feeder" POST "$FEEDER_URL/feeder/run?manifest=100&count=1" 200 '"requested"'
else
  skip "Mutating endpoint checks disabled (RUN_MUTATING=0)"
fi

echo
echo "7. Event-contract checks"
echo "------------------------"
if [[ "$RUN_EVENT_CHECKS" == "1" ]]; then
  if command -v mvn >/dev/null 2>&1; then
    if mvn -q -pl broker -am -Dtest=EventContractChecksTest -Dsurefire.failIfNoSpecifiedTests=false test; then
      pass "Event checks: duplicate handling, redelivery and DLQ"
    else
      fail "Event-contract tests failed"
    fi
  else
    skip "Event checks: Maven is not available"
  fi
else
  skip "Event checks disabled (RUN_EVENT_CHECKS=0)"
fi

echo
echo "8. Gateway checks"
echo "-----------------"
if [[ -n "$GATEWAY_URL" ]]; then
  check_json_field "Gateway search" GET "$GATEWAY_URL/search?q=sea" 200 '"results"'
  check_json_field "Gateway filters" GET "$GATEWAY_URL/search/filters" 200 '"languages"'
  check_json_field "Gateway invalid book id" GET "$GATEWAY_URL/books/abc" 400 '"error":"invalid_book_id"'
else
  skip "Gateway checks: set GATEWAY_URL when issue #7 is merged"
fi

echo
echo "========================================"
echo " Results"
echo "========================================"
echo "PASS: $PASS"
echo "FAIL: $FAIL"
echo "SKIP: $SKIP"
echo

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
