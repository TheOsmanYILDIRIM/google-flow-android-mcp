#!/usr/bin/env python3
"""
NotebookLM Browser Automation & API Sniffer Helper (v4.0)
Uses Android Browser Bridge to interact with NotebookLM, query notebooks, and sniff responses.
"""

import sys
import os
import time
import json
import argparse
import urllib.request
import urllib.parse

BASE_URL = os.environ.get("BROWSER_BRIDGE_URL", "http://127.0.0.1:8765")

def req(endpoint: str, method: str = "GET", data: dict = None, timeout: int = 20):
    url = f"{BASE_URL}{endpoint}"
    req_data = json.dumps(data).encode("utf-8") if data is not None else None
    request = urllib.request.Request(url, data=req_data, headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))

def open_notebooklm():
    print("📖 Opening NotebookLM in Android Browser...")
    req("/api/navigate", method="POST", data={"url": "https://notebooklm.google.com/"})
    time.sleep(4)
    status = req("/api/status")
    print(f"✓ Loaded: {status.get('pageTitle')} ({status.get('currentUrl')})")

def extract_notebooks():
    print("🔍 Extracting Notebook list from DOM & Network...")
    dom = req("/api/dom?format=interactive")
    elements = dom.get("elements", [])
    notebook_elements = [e for e in elements if "notebook" in (e.get("text") or "").lower() or e.get("type") == "button"]
    print(f"✓ Found {len(notebook_elements)} candidate notebook elements:")
    for ne in notebook_elements[:10]:
        print(f"  • [{ne.get('index')}] {ne.get('selector')} -> '{ne.get('text')}'")
    return notebook_elements

def sniff_notebook_traffic(keyword: str = "batchexecute"):
    print(f"📡 Sniffing NotebookLM API traffic (filter: '{keyword}')...")
    traffic = req(f"/api/traffic?filter={urllib.parse.quote(keyword)}&limit=20")
    print(f"✓ Captured {len(traffic)} matching API requests:")
    for t in traffic:
        print(f"  [{t.get('method')}] {t.get('status')} {t.get('url')} ({t.get('durationMs')}ms)")
        if t.get("responseBody"):
            print(f"     Preview: {str(t.get('responseBody'))[:200]}")
    return traffic

def main():
    parser = argparse.ArgumentParser(description="NotebookLM Automation Helper")
    parser.add_argument("action", choices=["open", "list", "sniff", "cookies"], default="open", nargs="?")
    args = parser.parse_args()

    if args.action == "open":
        open_notebooklm()
    elif args.action == "list":
        extract_notebooks()
    elif args.action == "sniff":
        sniff_notebook_traffic()
    elif args.action == "cookies":
        cookies = req("/api/cookies?url=https://notebooklm.google.com")
        print("🍪 NotebookLM Session Cookies:")
        print(cookies.get("cookieHeader"))

if __name__ == "__main__":
    main()
