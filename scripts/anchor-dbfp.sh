#!/usr/bin/env bash
# Anchor Launcher — workspace DB fingerprint harness.
#
# Pulls the launcher Favorites DB off the device and dumps its desktop rows in a
# stable, diffable form, printing an md5 of that dump. Used to prove that a PURE
# rotation leaves the workspace DB byte-identical — the core safety invariant of
# the pre-computed transpose refactor (rotation must write NOTHING to the DB).
#
# The query runs host-side: modern Android images ship no sqlite3 binary, so the
# .db file is pulled via `run-as ... cat` and read with the host sqlite3 from the
# platform-tools SDK.
#
# Usage:
#   scripts/anchor-dbfp.sh [-t TRANSPORT] [-p PKG] [-o OUTFILE]
#
# Typical rotation check:
#   scripts/anchor-dbfp.sh -o /tmp/before.txt
#   ... rotate the device, let it settle ...
#   scripts/anchor-dbfp.sh -o /tmp/after.txt
#   diff /tmp/before.txt /tmp/after.txt && echo "DB byte-identical"

set -euo pipefail

TRANSPORT=1
PKG=se.willovapps.anchor.debug
OUT=

while getopts "t:p:o:h" opt; do
  case $opt in
    t) TRANSPORT=$OPTARG ;;
    p) PKG=$OPTARG ;;
    o) OUT=$OPTARG ;;
    h) sed -n '2,24p' "$0"; exit 0 ;;
    *) exit 2 ;;
  esac
done

SQLITE=$(command -v sqlite3 || true)
if [ -z "$SQLITE" ]; then
  echo "error: host sqlite3 not found on PATH" >&2
  exit 1
fi

# Locate the launcher DB (its name encodes the grid, e.g. launcher_11_5_4.db).
DB=$(adb -t "$TRANSPORT" shell run-as "$PKG" ls databases/ 2>/dev/null \
     | tr -d '\r' | grep -E '^launcher.*\.db$' | head -1)
if [ -z "$DB" ]; then
  echo "error: no launcher_*.db found for $PKG on transport $TRANSPORT" >&2
  exit 1
fi

TMP=$(mktemp -t anchor-dbfp.XXXXXX.db)
trap 'rm -f "$TMP"' EXIT

adb -t "$TRANSPORT" exec-out run-as "$PKG" cat "databases/$DB" > "$TMP" 2>/dev/null
if [ ! -s "$TMP" ]; then
  echo "error: failed to pull databases/$DB from $PKG" >&2
  exit 1
fi

# Desktop items only (container -100), ordered deterministically so the dump is
# stable regardless of SQLite row order. Includes the coordinate and span columns
# the transpose touches, plus identity columns so a re-homed or dropped item is
# immediately visible in a diff.
DUMP=$("$SQLITE" "$TMP" \
  "SELECT _id, screen, cellX, cellY, spanX, spanY, itemType, title, intent
   FROM favorites WHERE container = -100 ORDER BY _id;" 2>/dev/null)

if [ -z "$DUMP" ]; then
  echo "error: query returned no desktop rows from $DB" >&2
  exit 1
fi

ROWS=$(printf '%s\n' "$DUMP" | wc -l | tr -d ' ')
MD5=$(printf '%s\n' "$DUMP" | md5sum | cut -d' ' -f1)

if [ -n "$OUT" ]; then
  printf '%s\n' "$DUMP" > "$OUT"
fi

echo "db:    $DB"
echo "rows:  $ROWS"
echo "md5:   $MD5"
[ -n "$OUT" ] && echo "dump:  $OUT"
