#!/usr/bin/env python3
"""Install (or update) a KS plugin ZIP through the KS Remote Admin API, then show our plugin's status.

Credentials: env KS_ADMIN_URL and KS_ADMIN_AUTH, else `KS_ADMIN_URL:` / `KS_ADMIN_AUTH:` lines in
.local/LOCAL.md (gitignored). Never printed.
Usage: ks_install_plugin.py <zip> [plugin-id]   (default id: kiosk-phone)
Output is filtered to our plugin id and password-like fields are masked.
"""
import base64, json, os, pathlib, re, sys, urllib.request

def local(key):
    p = pathlib.Path(__file__).resolve().parent.parent / ".local" / "LOCAL.md"
    m = re.search(r"^\W*%s:\s*(\S+)" % key, p.read_text(), re.M) if p.exists() else None
    return m.group(1) if m else None

def call(url, path, body, token=None):
    req = urllib.request.Request(url.rstrip("/") + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit("%s %s: HTTP %d %s" % (path, "failed", e.code, e.read().decode()[:400]))

def mask(o):
    if isinstance(o, dict):
        return {k: "***" if re.search("pass|secret|token|key", k, re.I) else mask(v) for k, v in o.items()}
    return [mask(v) for v in o] if isinstance(o, list) else o

def main():
    zip_path = sys.argv[1]
    pid = sys.argv[2] if len(sys.argv) > 2 else "kiosk-phone"
    url = os.environ.get("KS_ADMIN_URL") or local("KS_ADMIN_URL")
    pw = os.environ.get("KS_ADMIN_AUTH") or local("KS_ADMIN_AUTH")
    token = call(url, "/api/login", {"password": pw})["token"]
    data = base64.b64encode(open(zip_path, "rb").read()).decode()
    call(url, "/api/commands/installPlugin", {"data": data, "trusted": True}, token)
    call(url, "/api/commands/enablePlugin", {"id": pid}, token)
    res = call(url, "/api/commands/listPlugins", {}, token)
    found = []
    def walk(o):
        if isinstance(o, dict):
            if o.get("id") == pid: found.append(mask(o))
            else: [walk(v) for v in o.values()]
        elif isinstance(o, list): [walk(v) for v in o]
    walk(res)
    print(json.dumps(found, indent=1)[:2000])

main()
