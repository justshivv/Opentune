#!/usr/bin/env python3
"""
Finds the words OpenTune shows, for translating.

    python3 scripts/i18n/extract.py            # writes scripts/i18n/keys.json, prints a summary
    python3 scripts/i18n/extract.py missing hi # the keys Hindi has no translation for yet

A key is a text's English wording exactly as the app shows it: a plain string in the code,
or the template of a com.opentune.i18n.Tr.f call ("{0} songs"). Translations go in
core/src/main/assets/i18n/<tag>.json, an object from key to translation; a key left out
shows in English. Code that only builds keys, logs or talks to services is skipped.
"""
import json, pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from kt_strings import scan, unescape

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = ['app/src/main/java', 'core/src/main/java']
ASSETS = ROOT / 'core/src/main/assets/i18n'
# Lines whose strings are never shown: logs, JSON and HTTP plumbing, routes.
SKIPLINE = re.compile(r'\b(Log\.[dwiev]|AppLog\.|DebugLog\.|TrackLog\.|println|Regex\(|toRegex|@Serializable|@SerialName|'
                      r'const val TAG|getString\(|optString|optJSONObject|optJSONArray|getJSONObject|getJSONArray|\.put\("|'
                      r'header\(|addHeader|setHeader|Uri\.parse|navigate\(|composable\(|page\(")')
# Parts of the code that never put words on the screen.
SKIPDIRS = tuple('core/src/main/java/com/opentune/' + d for d in (
    'data/innertube/potoken/', 'cast/Dlna.kt', 'cast/CastServer.kt', 'data/innertube/PlayerClient.kt', 'data/spotify/PlaylistCsv.kt',
    'data/Usage.kt', 'data/AppLog.kt', 'data/together/Schnorr.kt', 'i18n/', 'data/Http.kt',
))

def shown(t):
    if not re.search(r'[A-Za-z]{2,}', t): return False
    if re.search(r'://|^www\.|\.(com|org|net|io|json|kt|mp3|flac|m4a|png|jpg|webp|html|js)\b|^com\.|^android\.|^androidx\.|^org\.|^\w+/\w+$', t): return False
    if re.fullmatch(r'[a-z0-9_.:/\-#@?&=+]*', t): return False             # identifiers, keys, paths
    if re.fullmatch(r'[A-Z0-9_]+', t): return False                         # CONSTANTS
    if re.fullmatch(r'[a-z]+[A-Za-z0-9_]*', t): return False                # camelCase
    if re.fullmatch(r'[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*', t): return False    # PascalCase
    if re.fullmatch(r'[A-Z][a-z]+(-[A-Z][a-z]+)+', t): return False         # Header-Names
    if re.fullmatch(r'\d{3} [A-Z].*|[A-Z]+:[A-Z]+', t): return False        # HTTP status lines, iCal tags
    if re.search(r'[<>\\]|^\s*$|^%|\bfun\b|\bval\b', t): return False
    if re.search(r'Mozilla|KHTML|Linux; U;|q=0\.|urn:|&\w+;|charset=|DLNA\.|HTTP/1|SHA-?\d|AES/|withECDSA|^[A-Z]{2,}-[A-Z]{2,}', t): return False
    if re.search(r'[{}]', t) and not re.search(r'\{\d+\}', t): return False
    return True

TRF = re.compile(r'Tr\.f\(\s*$')

def keys():
    """Every key, in the order the code first uses it, with the file it is in."""
    found = {}
    for d in SOURCES:
        for f in sorted((ROOT / d).rglob('*.kt')):
            rel = str(f.relative_to(ROOT))
            if rel.startswith(SKIPDIRS): continue
            src = f.read_text(); lines = src.split('\n')
            for (ln, s, e, kind, parts) in scan(src):
                if kind != 'plain': continue
                text = unescape(''.join(p for _, p in parts))
                before = src[max(0, s - 40):s]
                if re.search(r'Tr\.[ft]\(\s*$', before):           # always a key, whatever it looks like
                    found.setdefault(text, rel); continue
                if SKIPLINE.search(lines[ln - 1]): continue
                if shown(text): found.setdefault(text, rel)
    return found

def main():
    k = keys()
    out = pathlib.Path(__file__).parent / 'keys.json'
    if len(sys.argv) > 2 and sys.argv[1] == 'missing':
        have = json.loads((ASSETS / f'{sys.argv[2]}.json').read_text()) if (ASSETS / f'{sys.argv[2]}.json').exists() else {}
        missing = [x for x in k if x not in have]
        json.dump(missing, sys.stdout, ensure_ascii=False, indent=0); print(f'\n{len(missing)} missing', file=sys.stderr)
        return
    out.write_text(json.dumps(sorted(k), ensure_ascii=False, indent=0) + '\n')
    print(len(k), 'keys ->', out.relative_to(ROOT))
    for f in sorted(ASSETS.glob('*.json')):
        have = json.loads(f.read_text()); done = sum(1 for x in k if x in have)
        print(f'{f.stem:6} {done:5}/{len(k)}')

if __name__ == '__main__':
    main()
