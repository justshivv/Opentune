"""Read-only check: Pages serves the login/dashboard interface and correct share image."""
from time import sleep, time
from pathlib import Path
from hashlib import sha256
from urllib.request import Request, urlopen
base = 'https://justshivv.github.io/Opentune/usage/'
for attempt in range(6):
    try:
        with urlopen(Request(base + '?private=' + str(int(time())), headers={'Cache-Control':'no-cache'}), timeout=20) as response:
            html = response.read().decode()
        assert 'id="owner-login"' in html and 'data-host="pages"' in html
        assert 'id="owner-dashboard" hidden' in html and 'name="password"' in html
        manifest = __import__('json').loads((Path(__file__).resolve().parents[1] / 'site/usage/assets.json').read_text())
        for name, expected in manifest['assets'].items():
            with urlopen(base + name, timeout=20) as response:
                assert sha256(response.read()).hexdigest() == expected
        home = 'https://justshivv.github.io/Opentune/'
        with urlopen(Request(home + '?preview=' + str(int(time())), headers={'Cache-Control':'no-cache'}), timeout=20) as response:
            metadata = response.read().decode()
        image = 'img/share-waveform-v2.jpg'
        assert f'content="{home}{image}"' in metadata and 'summary_large_image' in metadata
        with urlopen(home + image, timeout=20) as response:
            published = response.read()
        local = Path(__file__).resolve().parents[1] / 'site' / image
        assert sha256(published).digest() == sha256(local.read_bytes()).digest()
        print('Verified Pages login/dashboard assets and exact social preview image published.')
        break
    except Exception:
        if attempt == 5: raise
        sleep(10)
