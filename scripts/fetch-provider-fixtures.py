"""Fetch pinned upstream packages for compatibility tests; never execute their code here."""
from pathlib import Path
import hashlib
import urllib.request

PACKAGES = {
    "amazon": ("amzn", "39ec454b07b5d881aa844ab6f2aa5eed6cff120bdecf49d10bd2386355de3329"),
    "qobuz-web": ("qobuz-web", "d957759c849d9631caa029cf2279635d44bd468af11a2df9cd13088cd721afc2"),
    "tidal-web": ("tidal-web", "83e3aa93c59a8e16fa3da85d972cb8ba95e4f29992bd756b18f61269714080db"),
}
target = Path("app/build/provider-fixtures")
target.mkdir(parents=True, exist_ok=True)
for name, (archive, expected) in PACKAGES.items():
    url = f"https://raw.githubusercontent.com/spotiflacapp/SpotiFLAC-Extension/main/extensions/{archive}.sflx"
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    assert len(data) <= 8 * 1024 * 1024
    assert hashlib.sha256(data).hexdigest() == expected, f"{name}: upstream changed; review before updating the pin"
    (target / f"{name}.sflx").write_bytes(data)
    print(f"Verified {name}: {len(data)} bytes")
