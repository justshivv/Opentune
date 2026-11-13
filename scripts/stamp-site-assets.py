#!/usr/bin/env python3
"""Gives the website's own stylesheets and scripts a content fingerprint in their URL.

Pages and their assets were linked by fixed names (assets/site.css), so a browser
holding last week's stylesheet kept using it with this week's page: the new markup
drawn with the old styles. Run at deploy time, this rewrites every local .css and .js
link in site/*.html (not the web player or the dashboard, which version their own
files) to name?v=<first 10 hex digits of its SHA-256>. A changed file gets a new
URL and is fetched again; an unchanged one stays cached.
"""
import hashlib
import re
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent / 'site'
SKIP = {'player', 'usage'}
LINK = re.compile(r'(?P<attr>href|src)="(?P<url>(?!https?:|//|data:)[^"?#]+\.(?:css|js))"')


def stamp(page: Path) -> int:
    html = page.read_text()
    count = 0

    def replace(match: re.Match) -> str:
        nonlocal count
        target = (page.parent / match['url']).resolve()
        if not target.is_file():
            sys.exit(f'{page.relative_to(SITE)} links {match["url"]}, which does not exist')
        digest = hashlib.sha256(target.read_bytes()).hexdigest()[:10]
        count += 1
        return f'{match["attr"]}="{match["url"]}?v={digest}"'

    stamped = LINK.sub(replace, html)
    if stamped != html:
        page.write_text(stamped)
    return count


total = 0
for page in sorted(SITE.rglob('*.html')):
    if SKIP & set(page.relative_to(SITE).parts):
        continue
    n = stamp(page)
    if n:
        print(f'{page.relative_to(SITE)}: {n} asset link(s) stamped')
    total += n
if total == 0:
    sys.exit('No asset links found to stamp; the site layout changed?')
