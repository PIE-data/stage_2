#!/usr/bin/env bash

# Stop using an undefined variable.
set -u

SEARCH_URL="${SEARCH_URL:-http://localhost:7003}"
INDEXING_URL="${INDEXING_URL:-http://localhost:7002}"
INGESTION_URL="${INGESTION_URL:-http://localhost:7001}"
FEEDER_URL="${FEEDER_URL:-http://localhost:7004}"

# Gateway is optional for now because issue #7 is still being implemented.
GATEWAY_URL="${GATEWAY_URL:-}"

# Mutating operations such as index update/rebuild are disabled by default.
RUN_MUTATING="${RUN_MUTATING:-0}"
BOOK_ID="${BOOK_ID:-1342}"

PASS=0
FAIL=0
SKIP=0

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT


pass() {
  echo "PASS  $1"
  PASS=$((PASS + 1))
}


fail() {
  echo "FAIL  $1"
  FAIL=$((FAIL + 1))
}


skip() {
  echo "SKIP  $1"
  SKIP=$((SKIP + 1))
}


check_status() {
  local name="$1"
  local method="$2"
  local url="$3"
  local expected="$4"

  local actual

  actual="$(
    curl \
      --silent \
      --show-error \
      --output "$BODY_FILE" \
      --write-out "%{http_code}" \
      --request "$method" \
      "$url" \
      2>/dev/null
  )" || actual="000"

  if [[ "$actual" == "$expected" ]]; then
    pass "$name -> HTTP $actual"
  else
    fail "$name -> expected HTTP $expected, got $actual"
    if [[ -s "$BODY_FILE" ]]; then
      echo "      Response: $(cat "$BODY_FILE")"
    fi
  fi
}


check_json_field() {
  local name="$1"
  local method="$2"
  local url="$3"
  local expected_status="$4"
  local expected_text="$5"

  local actual

  actual="$(
    curl \
      --silent \
      --show-error \
      --output "$BODY_FILE" \
      --write-out "%{http_code}" \
      --request "$method" \
      "$url" \
      2>/dev/null
  )" || actual="000"

  if [[ "$actual" != "$expected_status" ]]; then
    fail "$name -> expected HTTP $expected_status, got $actual"
    if [[ -s "$BODY_FILE" ]]; then
      echo "      Response: $(cat "$BODY_FILE")"
    fi
    return
  fi

  if grep -Fq "$expected_text" "$BODY_FILE"; then
    pass "$name -> HTTP $actual and expected response content"
  else
    fail "$name -> HTTP $actual but expected content was missing"
    echo "      Expected: $expected_text"
    echo "      Response: $(cat "$BODY_FILE")"
  fi
}


echo
echo "========================================"
echo " PIE-data Stage 2 API checks"
echo "========================================"
echo


echo "1. Health checks"
echo "----------------"

check_json_field \
  "Search /status" \
  GET \
  "$SEARCH_URL/status" \
  200 \
  '"service":"search"'

check_json_field \
  "Indexing /status" \
  GET \
  "$INDEXING_URL/status" \
  200 \
  '"service":"indexing"'

check_json_field \
  "Ingestion /status" \
  GET \
  "$INGESTION_URL/status" \
  200 \
  '"service":"ingestion"'

check_json_field \
  "Feeder /status" \
  GET \
  "$FEEDER_URL/status" \
  200 \
  '"service":"feeder"'


echo
echo "2. Search API"
echo "-------------"

check_json_field \
  "Basic search" \
  GET \
  "$SEARCH_URL/search?q=sea" \
  200 \
  '"results"'

check_json_field \
  "Search with language filter" \
  GET \
  "$SEARCH_URL/search?q=sea&language=en&offset=0&limit=20" \
  200 \
  '"query":"sea"'

check_json_field \
  "Available search filters" \
  GET \
  "$SEARCH_URL/search/filters" \
  200 \
  '"languages"'

check_json_field \
  "Missing q parameter" \
  GET \
  "$SEARCH_URL/search" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Invalid limit" \
  GET \
  "$SEARCH_URL/search?q=sea&limit=500" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Invalid language" \
  GET \
  "$SEARCH_URL/search?q=sea&language=english" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Invalid year" \
  GET \
  "$SEARCH_URL/search?q=sea&year=20" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Negative offset" \
  GET \
  "$SEARCH_URL/search?q=sea&offset=-1" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Duplicate q parameter" \
  GET \
  "$SEARCH_URL/search?q=sea&q=ship" \
  400 \
  '"error":"invalid_parameter"'

check_json_field \
  "Invalid book id" \
  GET \
  "$SEARCH_URL/books/abc" \
  400 \
  '"error":"invalid_book_id"'


echo
echo "3. Indexing API"
echo "---------------"

check_json_field \
  "Index status" \
  GET \
  "$INDEXING_URL/index/status" \
  200 \
  '"books_indexed"'


# Check validation of the update endpoint without modifying the datamart.
check_json_field \
  "Invalid index update book id" \
  POST \
  "$INDEXING_URL/index/update/0" \
  400 \
  '"error":"invalid_book_id"'


echo
echo "4. Mutating indexing checks"
echo "---------------------------"

if [[ "$RUN_MUTATING" == "1" ]]; then

  check_json_field \
    "Index book $BOOK_ID" \
    POST \
    "$INDEXING_URL/index/update/$BOOK_ID" \
    200 \
    '"index":"updated"'

  check_json_field \
    "Rebuild datamart" \
    POST \
    "$INDEXING_URL/index/rebuild" \
    200 \
    '"books_processed"'

else
  skip "POST /index/update/{book_id} success case (set RUN_MUTATING=1)"
  skip "POST /index/rebuild (set RUN_MUTATING=1)"
fi


echo
echo "5. Gateway checks"
echo "-----------------"

if [[ -n "$GATEWAY_URL" ]]; then

  check_status \
    "Search through gateway" \
    GET \
    "$GATEWAY_URL/search?q=sea" \
    200

  check_status \
    "Filters through gateway" \
    GET \
    "$GATEWAY_URL/search/filters" \
    200

else
  skip "Gateway checks (set GATEWAY_URL when gateway is available)"
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