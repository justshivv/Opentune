"""Kotlin string literal scanner: yields (path, line, start, end, kind, parts, line_text).
kind: 'plain' or 'template'; parts: list of ('s', text) / ('e', expr_source)."""
import re

def scan(src):
    i, n, line = 0, len(src), 1
    out = []
    while i < n:
        c = src[i]
        if c == '\n': line += 1; i += 1; continue
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); seg = src[i:(n if j < 0 else j + 2)]; line += seg.count('\n'); i += len(seg); continue
        if c == "'":
            m = re.match(r"'(\\u[0-9a-fA-F]{4}|\\.|[^'\\])'", src[i:])
            i += len(m.group(0)) if m else 1; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j >= 0 and src.startswith('""""', j): j += 1
            seg = src[i:j + 3]; line += seg.count('\n'); i = j + 3; continue  # raw strings skipped
        if c == '"':
            start, sline = i, line
            i += 1; parts, buf = [], ''
            while i < n and src[i] != '"':
                ch = src[i]
                if ch == '\\': buf += src[i:i+2]; i += 2; continue
                if ch == '$' and i + 1 < n and src[i+1] == '{':
                    depth, j = 1, i + 2
                    while j < n and depth:
                        if src[j] == '{': depth += 1
                        elif src[j] == '}': depth -= 1
                        elif src[j] == '"':  # nested string inside expression
                            k = j + 1
                            while k < n and src[k] != '"':
                                k += 2 if src[k] == '\\' else 1
                            j = k
                        j += 1
                    if buf: parts.append(('s', buf)); buf = ''
                    parts.append(('e', src[i+2:j-1])); i = j; continue
                if ch == '$' and i + 1 < n and (src[i+1].isalpha() or src[i+1] == '_'):
                    m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[i+1:])
                    if buf: parts.append(('s', buf)); buf = ''
                    parts.append(('e', m.group(0))); i += 1 + len(m.group(0)); continue
                if ch == '\n': line += 1
                buf += ch; i += 1
            if buf: parts.append(('s', buf))
            i += 1
            kind = 'template' if any(k == 'e' for k, _ in parts) else 'plain'
            out.append((sline, start, i, kind, parts))
            continue
        i += 1
    return out

def unescape(s):
    return (s.replace('\\"', '"').replace("\\'", "'").replace('\\n', '\n').replace('\\t', '\t')
             .replace('\\$', '$').replace('\\\\', '\\'))
