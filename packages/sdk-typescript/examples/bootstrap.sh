#!/usr/bin/env bash
#
# Creates an account, a workspace, a question bank with three questions, and an
# API key for the quickstart to use.
#
# Everything here is session-authenticated, because authoring records who wrote
# each version and an API key names a workspace rather than a person. That is
# the current rule; issue #98 is where it gets revisited.
#
#     ./examples/bootstrap.sh            # against http://localhost:8080
#     BASE_URL=… ./examples/bootstrap.sh
#
# It prints the environment variable the quickstart needs.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="quickstart-$(date +%s)@example.test"
PASSWORD="correct horse battery staple"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

# --fail-with-body so a broken step stops the script instead of quietly leaving
# a half-built workspace behind.
#
# The CSRF token arrives as a cookie and every session-authenticated write
# echoes it back in a header. An API key needs none of this.
csrf() { grep XSRF-TOKEN "$JAR" | awk '{print $7}'; }
api() {
  curl -sS --fail-with-body -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" "$@"
}
json() { api -H "Content-Type: application/json" "$@"; }
field() { python -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }

curl -sS -c "$JAR" -o /dev/null "$BASE_URL/v1/auth/health"

json -X POST "$BASE_URL/v1/auth/register" \
     -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Quickstart\"}" > /dev/null

# Registering does not sign you in. This is the call that issues the session.
json -X POST "$BASE_URL/v1/auth/login" \
     -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" > /dev/null

WORKSPACE=$(json -X POST "$BASE_URL/v1/workspaces" -d '{"name":"Quickstart"}' | field id)

# From here the workspace is named explicitly: a session can belong to several,
# so it says which one. A key never needs this header.
BANK=$(json -H "X-QuizForge-Workspace: $WORKSPACE" \
            -X POST "$BASE_URL/v1/question-banks" \
            -d '{"name":"Geography","description":"Capitals and rivers."}' | field id)

# Three questions, so a tournament can draw three. Note the Content-Type: this
# endpoint takes the CSV document itself, not JSON wrapping one.
REPORT=$(api -H "X-QuizForge-Workspace: $WORKSPACE" -H "Content-Type: text/csv" \
             -X POST "$BASE_URL/v1/question-banks/$BANK/imports/csv" \
             --data-binary $'type,prompt,options,correct,difficulty\nSINGLE_CHOICE,Capital of France?,Paris|Lyon|Nice,Paris,EASY\nSINGLE_CHOICE,Capital of Japan?,Tokyo|Osaka|Kyoto,Tokyo,EASY\nSHORT_TEXT,Longest river in Africa?,,Nile|The Nile,HARD\n')

SECRET=$(json -H "X-QuizForge-Workspace: $WORKSPACE" \
              -X POST "$BASE_URL/v1/api-keys" \
              -d '{"name":"quickstart","environment":"test"}' | field secret)

cat <<OUT
Workspace: $WORKSPACE
Bank:      $BANK
Import:    $REPORT

Now run the quickstart:

  export QUIZFORGE_API_KEY=$SECRET
  npm run build && node examples/quickstart.ts
OUT
