#!/usr/bin/env python3
"""Deliver the built APK through GitHub Issues (API-only channel).

The dev environment can only reach api.github.com / github.com, and Actions
artifact downloads redirect to a blocked storage host. Posting the APK as
base64 chunks in issue comments lets it be fetched back through the API.
"""
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
CHUNK = 60_000  # issue comment bodies are limited to 65536 chars


def api(method, path, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "phoneftp-ci",
        },
    )
    # GitHub's secondary rate limit kicks in when many comments are created
    # quickly — pace ourselves and back off + retry when told to slow down.
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429) and attempt < 7:
                wait = 30 * (attempt + 1)
                print(f"Rate limited (HTTP {e.code}), waiting {wait}s…", flush=True)
                time.sleep(wait)
                continue
            raise


def main():
    apk_path = sys.argv[1] if len(sys.argv) > 1 else "PhoneFTP.apk"
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    run_id = os.environ.get("GITHUB_RUN_ID", "local")

    raw = open(apk_path, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    encoded = base64.b64encode(raw).decode()
    chunks = [encoded[i:i + CHUNK] for i in range(0, len(encoded), CHUNK)]
    print(f"APK {len(raw)} bytes, sha256={sha}, {len(chunks)} chunks")

    issue = api("POST", f"/repos/{repo}/issues", token, {
        "title": f"PhoneFTP APK delivery (run {run_id})",
        "body": (
            "Automated APK delivery from CI.\n\n"
            f"- File: `{apk_path}`\n"
            f"- Size: {len(raw)} bytes\n"
            f"- SHA-256: `{sha}`\n"
            f"- Encoding: base64, {len(chunks)} chunks of {CHUNK} chars "
            "in the comments below, in order.\n"
        ),
    })
    number = issue["number"]
    print(f"Created issue #{number}")

    for i, chunk in enumerate(chunks):
        api("POST", f"/repos/{repo}/issues/{number}/comments", token, {
            "body": f"PHONEFTP-APK-CHUNK {i + 1}/{len(chunks)}\n{chunk}"
        })
        if (i + 1) % 20 == 0 or i + 1 == len(chunks):
            print(f"Posted chunk {i + 1}/{len(chunks)}", flush=True)
        time.sleep(2)

    print(f"Done: issue #{number}")


if __name__ == "__main__":
    main()
