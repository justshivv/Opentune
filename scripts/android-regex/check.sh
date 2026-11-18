#!/bin/bash
# Compiles every regex literal in the Android modules with ICU 60, the regex engine of Android 9.
# Tests run on the JVM, whose regex accepts syntax older Android rejects (\p{IsHan}, for one), so
# a pattern can pass every test and still throw on a phone. Android 8 (minSdk 26) has ICU 58,
# a release earlier: what ICU 60 refuses, Android 8 refuses too.
#
#   scripts/android-regex/check.sh     # needs curl, dpkg-deb, gcc and python3 (Linux x86-64)
#
# Prints each pattern ICU refuses, with its file and line, and exits 1 if there are any.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DEB=libicu60_60.2-3ubuntu3.2_amd64.deb
SHA=f01f61f57e63dc905e02e6441d10370fe0c79e43e70e6f5fe24b342fd2633209
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -sSfL -o "$WORK/$DEB" "http://archive.ubuntu.com/ubuntu/pool/main/i/icu/$DEB"
echo "$SHA  $WORK/$DEB" | sha256sum -c - >/dev/null
dpkg-deb -x "$WORK/$DEB" "$WORK/icu"
LIB="$WORK/icu/usr/lib/x86_64-linux-gnu"
gcc -O1 -o "$WORK/check" "$HERE/icu_check.c" "$LIB/libicui18n.so.60" "$LIB/libicuuc.so.60" -Wl,-rpath,"$LIB"
python3 -I "$HERE/extract.py" "$REPO" "$WORK/patterns.json" > "$WORK/patterns.txt"
LD_LIBRARY_PATH="$LIB" "$WORK/check" < "$WORK/patterns.txt" > "$WORK/results.txt"
total=$(wc -l < "$WORK/patterns.txt")
# A checker that didn't run says nothing, which must not read as "all fine".
if [ "$(wc -l < "$WORK/results.txt")" -ne "$total" ]; then echo "The ICU checker answered for $(wc -l < "$WORK/results.txt") of $total patterns." >&2; exit 2; fi
grep -v $'\tok$' "$WORK/results.txt" > "$WORK/failed.txt" || true
if [ ! -s "$WORK/failed.txt" ]; then echo "All $total regexes compile on ICU 60."; exit 0; fi
python3 -I - "$WORK" <<'PY'
import json, sys
work = sys.argv[1]
patterns = json.load(open(f'{work}/patterns.json'))
for line in open(f'{work}/failed.txt'):
    i, why = line.rstrip('\n').split('\t', 1)
    where, _, text, _ = patterns[int(i)]
    print(f'{where}: {why}\n    {text!r}')
PY
exit 1
