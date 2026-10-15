"""Check SEO metadata and exported store-package consistency without network access."""
import json
import re
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://justshivv.github.io/Opentune/'

class Page(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.tags = []
        self.ld = []
        self.in_ld = False
        self.feed(text)
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.tags.append((tag, attrs))
        if tag == 'script' and attrs.get('type') == 'application/ld+json': self.in_ld = True
    def handle_endtag(self, tag):
        if tag == 'script': self.in_ld = False
    def handle_data(self, text):
        if self.in_ld: self.ld.append(json.loads(text))
    def matches(self, tag, **attrs):
        return [a for t, a in self.tags if t == tag and all(a.get(k) == v for k,v in attrs.items())]

home = Page((ROOT / 'site/index.html').read_text())
assert len(home.matches('h1')) == 1
assert len(home.matches('link', rel='canonical', href=BASE)) == 1
assert len(home.matches('meta', name='description')) == 1
assert 100 <= len(home.matches('meta', name='description')[0]['content']) <= 170
assert home.matches('meta', name='twitter:title') and home.matches('meta', name='twitter:description')
assert home.matches('meta', property='og:image')[0]['content'] == BASE + 'img/share-waveform-v2.jpg'
app = next(x for x in home.ld if x['@type'] == 'SoftwareApplication')
assert app['name'] == 'OpenTune' and app['url'] == BASE and app['offers']['price'] == '0'
assert 'aggregateRating' not in app
assert app['downloadUrl'] == 'https://github.com/justshivv/Opentune/releases/latest'
assert len(home.matches('details')) >= 6
assert home.matches('a', href='privacy/')
for tag, attrs in home.tags:
    if tag == 'img': assert 'alt' in attrs
privacy = Page((ROOT / 'site/privacy/index.html').read_text())
assert privacy.matches('link', rel='canonical', href=BASE + 'privacy/')
urls = [node.text for node in ElementTree.parse(ROOT / 'site/sitemap.xml').iter('{http://www.sitemaps.org/schemas/sitemap/0.9}loc')]
assert set(urls) == {BASE, BASE + 'privacy/'}
assert not any('/usage' in u or '/together' in u for u in urls)
with tempfile.TemporaryDirectory() as directory:
    subprocess.run([sys.executable, str(ROOT / 'scripts/prepare-store-package.py'), '--commit', 'a' * 40, '--output', directory], check=True)
    output = Path(directory)
    manifest = json.loads((output / 'release.json').read_text())
    gradle = (ROOT / 'app/build.gradle.kts').read_text()
    assert manifest['versionName'] == re.search(r'versionName = "([^"]+)"', gradle)[1]
    assert manifest['versionCode'] == int(re.search(r'versionCode = (\d+)', gradle)[1])
    assert manifest['packageName'] == 'com.opentune'
    recipe = (output / 'fdroid/com.opentune.yml').read_text()
    assert '@COMMIT@' not in recipe and '@VERSION@' not in recipe and '@VERSION_CODE@' not in recipe
    assert 'a' * 40 in recipe and 'unsignedRelease=true' in recipe and 'universalOnly=true' in recipe
    assert (output / 'PUBLISHING.md').is_file()
print('SEO and store-package checks passed; metadata matches Gradle and no private routes are indexed.')
