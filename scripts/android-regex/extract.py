"""Every regex literal in the Android modules' Kotlin, as id<TAB>flags<TAB>pattern lines for icu_check.c. Usage: extract.py <repo> <patterns.json>."""
import re, sys, pathlib, json
ROOT = pathlib.Path(sys.argv[1])
MODULES = ['app/src/main', 'core/src/main', 'tv/src/main', 'wear/src/main']

def read_literal(src, i):
    """The string literal starting at src[i] (after spaces), as (text, end, templated) or None."""
    while i < len(src) and src[i] in ' \t\n': i += 1
    if src.startswith('"""', i):
        j = src.find('"""', i + 3)
        while j >= 0 and src.startswith('""""', j): j += 1
        raw = src[i + 3:j]
        templated = bool(re.search(r'\$(\{(?!\'\$\'\})|[A-Za-z_])', raw))
        raw = raw.replace("${'$'}", '$')
        raw = re.sub(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*', 'x', raw)
        return raw, j + 3, templated
    if i < len(src) and src[i] == '"':
        j, out, templated = i + 1, [], False
        while j < len(src) and src[j] != '"':
            c = src[j]
            if c == '\\':
                e = src[j + 1]
                if e == 'u': out.append(chr(int(src[j + 2:j + 6], 16))); j += 6; continue
                out.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', '\\': '\\', '"': '"', "'": "'", '$': '$'}.get(e, e)); j += 2; continue
            if c == '$' and j + 1 < len(src) and (src[j + 1] == '{' or src[j + 1].isalpha() or src[j + 1] == '_'):
                templated = True
                if src[j + 1] == '{':
                    depth, k = 1, j + 2
                    while depth: depth += {'{': 1, '}': -1}.get(src[k], 0); k += 1
                    j = k
                else:
                    k = j + 1
                    while k < len(src) and (src[k].isalnum() or src[k] == '_'): k += 1
                    j = k
                out.append('x'); continue
            out.append(c); j += 1
        return ''.join(out), j + 1, templated
    return None

def concat(src, i):
    """A literal, or literals joined with +, from src[i]."""
    first = read_literal(src, i)
    if not first: return None
    text, end, templated = first
    while True:
        m = re.match(r'\s*\+\s*', src[end:])
        if not m: break
        nxt = read_literal(src, end + m.end())
        if not nxt: templated = True; break
        text += nxt[0]; end = nxt[1]; templated |= nxt[2]
    return text, end, templated

found = []
for mod in MODULES:
    for f in sorted((ROOT / mod).rglob('*.kt')):
        src = f.read_text()
        rel = str(f.relative_to(ROOT))
        for m in re.finditer(r'\b(?:Regex|Pattern\.compile)\s*\(', src):
            lit = concat(src, m.end())
            if lit is None: continue
            text, end, templated = lit
            rest = src[end:end + 200]
            flags = 4 if 'RegexOption.COMMENTS' in rest.split(')')[0] or 'Pattern.COMMENTS' in rest.split(')')[0] else 0
            line = src.count('\n', 0, m.start()) + 1
            found.append((f'{rel}:{line}', flags, text, templated))
        for m in re.finditer(r'\.toRegex\s*\(', src):
            # the literal that ends right before .toRegex
            k = m.start() - 1
            if src[k] != '"': continue
            if src.endswith('"""', 0, k + 1):
                start = src.rfind('"""', 0, k - 2)
            else:
                start = k - 1
                while start >= 0 and not (src[start] == '"' and src[start - 1] != '\\'): start -= 1
            lit = read_literal(src, start)
            if lit is None: continue
            line = src.count('\n', 0, m.start()) + 1
            found.append((f'{rel}:{line}', 0, lit[0], lit[2]))
json.dump(found, open(sys.argv[2], 'w'), ensure_ascii=False)
for i, (where, flags, text, templated) in enumerate(found):
    print(f'{i}\t{flags}\t' + text.replace('\\', '\\\\').replace('\n', '\\n'))
