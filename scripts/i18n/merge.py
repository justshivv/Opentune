#!/usr/bin/env python3
"""
Adds translations to a language's file, checking each one first.

    python3 scripts/i18n/merge.py <numbered-keys.json> <translations.txt>...

<numbered-keys.json> is a JSON list of English keys. In <translations.txt> a line
"@@ <tag>" starts a language's part, and each line after it is "N|translation" for the
key at index N (\\n in a line stands for a line break).
A translation is refused when its {0}, {1}… places differ from the English, or it is
empty. Existing translations of other keys are kept.
"""
import json, pathlib, re, sys

ASSETS = pathlib.Path(__file__).resolve().parents[2] / 'core/src/main/assets/i18n'
PLACE = re.compile(r'\{\d+\}')
PRINTF = re.compile(r'%[-+ 0#]*\d*(?:\.\d+)?[dsf]')

def merge(tag, keys, lines):
    target = ASSETS / f'{tag}.json'
    table = json.loads(target.read_text()) if target.exists() else {}
    added, refused = 0, []
    for raw in lines:
        if not raw.strip() or '|' not in raw: continue
        n, text = raw.split('|', 1)
        if not n.strip().isdigit(): continue
        i = int(n)
        if i >= len(keys): refused.append((i, 'no such key')); continue
        key = keys[i]
        text = text.strip().replace('\\n', '\n').replace('\\"', '"')
        if not text: refused.append((i, 'empty')); continue
        if sorted(PLACE.findall(text)) != sorted(PLACE.findall(key)): refused.append((i, f'places differ: {key!r} -> {text!r}')); continue
        if sorted(PRINTF.findall(text)) != sorted(PRINTF.findall(key)): refused.append((i, f'formats differ: {key!r} -> {text!r}')); continue
        # A fragment joined to others keeps the English one's spaces at its ends.
        lead, trail = key[:len(key) - len(key.lstrip())], key[len(key.rstrip()):]
        table[key] = lead + text + trail; added += 1
    target.write_text(json.dumps(dict(sorted(table.items())), ensure_ascii=False, indent=0) + '\n')
    print(f'{tag}: {added} added, {len(table)} in all')
    for i, why in refused: print(f'  refused {i}: {why}')

def main(keys_file, *files):
    keys = json.loads(pathlib.Path(keys_file).read_text())
    for f in files:
        tag, part = None, []
        for raw in pathlib.Path(f).read_text().splitlines():
            if raw.startswith('@@'):
                if tag: merge(tag, keys, part)
                tag, part = raw[2:].strip(), []
            else: part.append(raw)
        if tag: merge(tag, keys, part)

if __name__ == '__main__':
    main(*sys.argv[1:])
