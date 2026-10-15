"""Validate the shared card locally, or fetch the deployed card as a link crawler."""
import argparse
import struct
from html.parser import HTMLParser
from pathlib import Path
from urllib.request import Request, urlopen

BASE = 'https://justshivv.github.io/Opentune/'
ROOT = Path(__file__).resolve().parents[1]

class Metadata(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.meta = {}
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'meta':
            self.meta[attrs.get('property', attrs.get('name'))] = attrs.get('content')

def dimensions(data):
    assert data[:2] == b'\xff\xd8', 'Share image must be a real JPEG'
    position = 2
    while position + 4 < len(data):
        assert data[position] == 0xff
        marker = data[position + 1]
        length = struct.unpack('>H', data[position + 2:position + 4])[0]
        if marker in (0xc0, 0xc2):
            height, width = struct.unpack('>HH', data[position + 5:position + 9])
            return width, height
        position += 2 + length
    raise AssertionError('No JPEG image dimensions')

def validate(html, data):
    meta = Metadata(html).meta
    url = BASE + 'img/share-waveform-v2.jpg'
    assert meta['og:image'] == meta['og:image:secure_url'] == meta['twitter:image'] == url
    assert html.index('property="og:image"') < 4096, 'Place preview metadata before large embedded assets'
    assert meta['og:image:type'] == 'image/jpeg'
    assert meta['twitter:card'] == 'summary_large_image'
    assert meta['og:title'] and meta['og:description']
    assert len(data) < 300_000, 'Keep the social image small enough for messaging crawlers'
    width, height = dimensions(data)
    assert (width, height) == (int(meta['og:image:width']), int(meta['og:image:height']))
    assert width >= 600 and height >= 315
    print(f'Social preview valid: {width}x{height}, {len(data):,} bytes')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--published', action='store_true')
    args = parser.parse_args()
    if args.published:
        def get(url):
            req = Request(url, headers={'User-Agent': 'facebookexternalhit/1.1'})
            with urlopen(req, timeout=30) as response:
                assert response.status == 200
                return response.headers.get_content_type(), response.read()
        kind, page = get(BASE)
        assert kind == 'text/html'
        kind, data = get(BASE + 'img/share-waveform-v2.jpg')
        assert kind == 'image/jpeg'
        validate(page.decode(), data)
    else:
        validate((ROOT / 'site/index.html').read_text(), (ROOT / 'site/img/share-waveform-v2.jpg').read_bytes())
