#!/usr/bin/env python3
"""A small OpenTune addon that shares a folder of music you own or may share.

Name files "Artist - Title.ext" (MP3, M4A, FLAC, Ogg, Opus or WAV), then run:

    python3 example_server.py ~/Music/my-releases --port 8080

Add http://<this computer's address>:8080 in OpenTune under Settings ->
Connected services -> Addons while you test on your home network. For
anyone else, put it behind HTTPS and pass --public-url https://your.domain

Only Python's standard library is used. See docs/addons.md for the protocol.
"""
import argparse
import http.server
import json
import mimetypes
import os
import re
import urllib.parse

AUDIO = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav"}


def load_tracks(folder):
    tracks = {}
    for root, _, files in os.walk(folder):
        for name in sorted(files):
            stem, ext = os.path.splitext(name)
            if ext.lower() not in AUDIO:
                continue
            path = os.path.join(root, name)
            track_id = os.path.relpath(path, folder).replace(os.sep, "/")
            artist, _, title = stem.partition(" - ")
            if not title:
                artist, title = "", stem
            tracks[track_id] = {"id": track_id, "title": title.strip(), "artist": artist.strip(), "path": path}
    return tracks


class Addon(http.server.BaseHTTPRequestHandler):
    tracks = {}
    manifest = {}
    public_url = ""

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/manifest.json":
            return self.send_json(self.manifest)
        if url.path == "/search":
            query = urllib.parse.parse_qs(url.query)
            words = re.findall(r"\w+", query.get("q", [""])[0].lower())
            limit = min(int(query.get("limit", ["20"])[0] or 20), 50)
            found = [
                {k: t[k] for k in ("id", "title", "artist")}
                for t in self.tracks.values()
                if all(w in (t["artist"] + " " + t["title"]).lower() for w in words)
            ]
            return self.send_json({"tracks": found[:limit]})
        if url.path.startswith("/stream/"):
            track = self.tracks.get(urllib.parse.unquote(url.path[len("/stream/"):]))
            if not track:
                return self.send_json({"error": "not found"}, 404)
            file_url = self.base() + "/files/" + urllib.parse.quote(track["id"])
            return self.send_json({"url": file_url, "mimeType": mime_of(track["path"])})
        if url.path.startswith("/files/"):
            track = self.tracks.get(urllib.parse.unquote(url.path[len("/files/"):]))
            if not track:
                return self.send_json({"error": "not found"}, 404)
            return self.send_file(track["path"])
        self.send_json({"error": "not found"}, 404)

    def base(self):
        return self.public_url or "http://" + self.headers.get("Host", "localhost")

    def send_json(self, body, status=200):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_file(self, path):
        # Range requests let the player seek without fetching the whole file.
        size = os.path.getsize(path)
        start, end = 0, size - 1
        match = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if match and (match.group(1) or match.group(2)):
            if match.group(1):
                start = int(match.group(1))
                end = int(match.group(2)) if match.group(2) else size - 1
            else:
                start = max(0, size - int(match.group(2)))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", mime_of(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def mime_of(path):
    ext = os.path.splitext(path)[1].lower()
    return {".flac": "audio/flac", ".opus": "audio/ogg", ".m4a": "audio/mp4"}.get(ext) or mimetypes.guess_type(path)[0] or "audio/mpeg"


def main():
    p = argparse.ArgumentParser(description="Share a folder of your own music as an OpenTune addon.")
    p.add_argument("folder")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--id", default="my-music")
    p.add_argument("--name", default="My music")
    p.add_argument("--catalogue", default="Music I made or have the rights to share.")
    p.add_argument("--public-url", default="", help="The https address people reach this server at")
    args = p.parse_args()
    Addon.tracks = load_tracks(args.folder)
    Addon.public_url = args.public_url.rstrip("/")
    Addon.manifest = {"opentune": 1, "id": args.id, "name": args.name, "version": "1.0.0", "catalogue": args.catalogue}
    print(f"Sharing {len(Addon.tracks)} tracks on port {args.port}")
    http.server.ThreadingHTTPServer(("", args.port), Addon).serve_forever()


if __name__ == "__main__":
    main()
