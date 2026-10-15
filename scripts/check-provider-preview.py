"""Diagnostic only: never print session material, challenge IDs or response bodies."""
import json
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect())
base = "https://api.zarz.moe/v2"
query = urllib.parse.urlencode({"install_id": secrets.token_hex(16), "app_version": "amzn@2.3.11"})
for label, url in [("health", base + "/health"), ("bootstrap", base + "/bootstrap?" + query)]:
    report = {"check": label}
    try:
        try:
            response = opener.open(urllib.request.Request(url, headers={"User-Agent": "OpenTune-provider-preview-check"}), timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            report["http_status"] = response.status
            raw = response.read(131073)
        data = json.loads(raw) if len(raw) <= 131072 else {}
        if isinstance(data, dict):
            code = data.get("code", "")
            if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,80}", code):
                report["code"] = code
            if label == "bootstrap":
                report["has_session"] = bool(data.get("session_id") and data.get("session_secret"))
                report["has_challenge_id"] = bool(data.get("challenge_id"))
                challenge = data.get("auth_url") or data.get("challenge_url")
                if isinstance(challenge, str):
                    parsed = urllib.parse.urlsplit(challenge)
                    report["challenge_origin_matches"] = parsed.scheme == "https" and parsed.hostname == "api.zarz.moe" and parsed.port in (None, 443)
                    report["challenge_path_matches"] = parsed.path == "/v2/challenge"
    except Exception as error:
        report["unavailable"] = type(error).__name__
    print(json.dumps(report, sort_keys=True))
print("This diagnostic does not establish browser verification or lossless playback.")
