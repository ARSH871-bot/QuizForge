#!/usr/bin/env bash
#
# The getting-started walkthrough, as a script.
#
# Runs every call in README.md in order, against a running instance, and prints
# what each one returns. Nothing is mocked and nothing is seeded: this is the
# same path a new developer takes by hand.
#
#     ./docs/api/quickstart.sh              # against http://localhost:8080
#     QF=http://localhost:8081 ./docs/api/quickstart.sh
#
# It registers a new account each run, so it can be run repeatedly against the
# same database.
set -euo pipefail

QF="${QF:-http://localhost:8080}"
EMAIL="ada-$(date +%s)@example.com"
PASSWORD="correct horse battery staple"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
say() { printf "\n=== %s ===\n" "$1"; }


say "health"
curl -s "$QF/v1/auth/health"; echo

say "register"
curl -s -c "$JAR" -o /dev/null "$QF/v1/auth/health"
CSRF() { grep XSRF-TOKEN "$JAR" | awk '{print $7}'; }
curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/auth/register" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -d '{"email":"'"$EMAIL"'","password":"'"$PASSWORD"'","displayName":"Ada"}'
echo

say "login"
curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -d '{"email":"'"$EMAIL"'","password":"'"$PASSWORD"'"}'
echo

say "create workspace"
WS=$(curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/workspaces" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -d '{"name":"Ada Quizzes"}')
echo "$WS"
WSP=$(echo "$WS" | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

say "create bank"
BK=$(curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/question-banks" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"name":"Geography","description":"Capitals and rivers."}')
echo "$BK"
BANK=$(echo "$BK" | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

say "import csv (one row deliberately broken)"
curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/question-banks/$BANK/imports/csv" \
  -H "Content-Type: text/csv" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" \
  --data-binary $'type,prompt,options,correct,difficulty\nSINGLE_CHOICE,Capital of France?,Paris|Lyon|Nice,Paris,EASY\nSINGLE_CHOICE,Capital of Japan?,Tokyo|Osaka|Kyoto,Tokyo,EASY\nNUMERIC,How many continents?,,seven,EASY\nSHORT_TEXT,Longest river in Africa?,,Nile|The Nile,HARD\n'
echo

say "author one question by hand"
curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/question-banks/$BANK/questions" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"type":"TRUE_FALSE","prompt":"Canberra is the capital of Australia.","difficulty":"EASY","payload":{"kind":"choice","options":[{"text":"True","correct":true},{"text":"False","correct":false}]}}'
echo

say "list questions"
curl -s -b "$JAR" "$QF/v1/question-banks/$BANK/questions?limit=2" \
  -H "X-QuizForge-Workspace: $WSP"
echo

say "mint an api key"
KEYJSON=$(curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/api-keys" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" \
  -d '{"name":"laptop","environment":"test"}')
echo "$KEYJSON"
KEY=$(echo "$KEYJSON" | python -c 'import json,sys;print(json.load(sys.stdin)["secret"])')

say "read with the key (no cookie, no workspace header)"
curl -s "$QF/v1/question-banks" -H "Authorization: Bearer $KEY"
echo

say "write with the key"
curl -s "$QF/v1/question-banks" -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" -X POST -d '{"name":"By key"}'
echo

say "rate limit headers"
curl -s -D - -o /dev/null "$QF/v1/question-banks" -H "Authorization: Bearer $KEY" | grep -i "^ratelimit"

say "create tournament"
OPENS=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
CLOSES=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=7)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
DRAFT="{\"name\":\"Geography Weekly\",\"bankId\":\"$BANK\",\"opensAt\":\"$OPENS\",\"closesAt\":\"$CLOSES\",\"questions\":3,\"timeLimitSeconds\":600,\"maxAttempts\":2,\"scoringPolicy\":\"BEST\"}"
TN=$(curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/tournaments" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" -H "Idempotency-Key: weekly-2026-w36" \
  -d "$DRAFT")
echo "$TN"
TID=$(echo "$TN" | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

say "replay the same idempotency key"
curl -s -D - -o /dev/null -b "$JAR" -c "$JAR" -X POST "$QF/v1/tournaments" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
  -H "X-QuizForge-Workspace: $WSP" -H "Idempotency-Key: weekly-2026-w36" \
  -d "$DRAFT" | grep -i "^HTTP/\|^idempotency-replayed"

say "start an attempt"
AT=$(curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/tournaments/$TID/attempts" \
  -H "X-XSRF-TOKEN: $(CSRF)" -H "X-QuizForge-Workspace: $WSP")
echo "$AT"
AID=$(echo "$AT" | python -c 'import json,sys;print(json.load(sys.stdin)["id"])')

say "play three questions"
for p in 1 2 3; do
  Q=$(curl -s -b "$JAR" "$QF/v1/attempts/$AID/questions/$p" -H "X-QuizForge-Workspace: $WSP")
  echo "$Q"
  A=$(echo "$Q" | python -c 'import json,sys
q=json.load(sys.stdin)
opts=q.get("options") or []
print(opts[0] if opts else "Nile")')
  curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/attempts/$AID/questions/$p/answer" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(CSRF)" \
    -H "X-QuizForge-Workspace: $WSP" -d "{\"answer\":\"$A\"}"
  echo
done

say "submit"
curl -s -b "$JAR" -c "$JAR" -X POST "$QF/v1/attempts/$AID/submit" \
  -H "X-XSRF-TOKEN: $(CSRF)" -H "X-QuizForge-Workspace: $WSP"
echo

say "standings"
curl -s -b "$JAR" "$QF/v1/tournaments/$TID/standings" -H "X-QuizForge-Workspace: $WSP"
echo
rm -f "$JAR"
