"""Exercise the actual VPS dashboard/server with a temporary database, no upstream calls."""
import os
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import urlopen
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'website-reports'
OUT.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='opentune-controls-') as directory:
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    env = dict(os.environ, HOST='127.0.0.1', PORT=str(port), DATA_DIR=directory,
               PUBLIC_HOST='addon.example', CONTROL_ADMIN_USERNAME='test-owner',
               CONTROL_ADMIN_PASSWORD='test-only-long-password')
    server = subprocess.Popen(['node', str(ROOT / 'services/lossless-addon/server.mjs')], env=env)
    try:
        deadline = time.monotonic() + 20
        while True:
            try:
                with urlopen(base + '/control/') as response:
                    assert response.status == 200
                break
            except OSError:
                if time.monotonic() > deadline or server.poll() is not None:
                    raise RuntimeError('Dashboard test server did not start')
                time.sleep(.1)
        with sync_playwright() as p:
            browser = p.chromium.launch()
            for width in (320, 1280):
                context = browser.new_context(viewport={'width': width, 'height': 900})
                # Browser-facing HTTPS matches production's reverse proxy. All requests stay local.
                def route(request):
                    target = urlparse(request.request.url)
                    if target.netloc != 'addon.example':
                        request.abort()
                        return
                    response = request.fetch(url=base + target.path, max_redirects=0)
                    request.fulfill(response=response)
                context.route('**/*', route)
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto('https://addon.example/control/')
                page.get_by_label('Username', exact=True).fill('test-owner')
                page.get_by_label('Password', exact=True).fill('test-only-long-password')
                page.get_by_role('button', name='Sign in', exact=True).click()
                expect(page.locator('#editor')).to_be_visible()
                page.get_by_label('Manage the published add-on catalog here').check()
                page.get_by_role('button', name='Add an add-on', exact=True).click()
                source = page.locator('#sources .source').last
                source.get_by_label('Display name').fill('Community test source')
                source.get_by_label('Manifest URL').fill('https://source.example/manifest.json')
                source.get_by_label('Available to listeners').uncheck()
                page.get_by_role('button', name='Add a sample banner').click()
                page.get_by_role('button', name='Refresh preview', exact=True).click()
                expect(page.locator('#preview')).to_contain_text('Made for music')
                page.get_by_role('button', name='Publish changes', exact=True).click()
                expect(page.locator('#status')).to_contain_text('Published.')
                expect(page.locator('#sources .source').last.get_by_label('Available to listeners')).not_to_be_checked()
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
                assert page.evaluate('localStorage.length === 0 && sessionStorage.length === 0')
                page.screenshot(path=str(OUT / f'app-controls-{width}.png'), full_page=True)
                page.get_by_label('Show the existing monthly community invitations').uncheck()
                page.get_by_role('button', name='Publish changes', exact=True).click()
                expect(page.locator('#status')).to_contain_text('Published.')
                page.on('dialog', lambda dialog: dialog.accept())
                page.locator('#history button:enabled').first.click()
                expect(page.locator('#status')).to_contain_text('Restored')
                expect(page.get_by_label('Show the existing monthly community invitations')).to_be_checked()
                page.get_by_role('button', name='Sign out', exact=True).click()
                expect(page.locator('#login')).to_be_visible()
                assert not errors, errors
                context.close()
            browser.close()
    finally:
        server.terminate()
        server.wait(timeout=10)
print('Owner login, publication, pause, rollback and responsive layout passed.')
