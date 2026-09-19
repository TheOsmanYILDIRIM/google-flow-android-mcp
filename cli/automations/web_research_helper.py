#!/usr/bin/env python3
"""
Web Research & Deep Sniffer Helper (v4.0)
Navigates to any target web page, sniffs internal API JSON responses, and exports clean DOM and markdown.
"""

import sys
import os
import time
import json
import argparse
import urllib.request
import urllib.parse

BASE_URL = os.environ.get("BROWSER_BRIDGE_URL", "http://127.0.0.1:8765")

def req(endpoint: str, method: str = "GET", data: dict = None, timeout: int = 25):
    url = f"{BASE_URL}{endpoint}"
    req_data = json.dumps(data).encode("utf-8") if data is not None else None
    request = urllib.request.Request(url, data=req_data, headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))

def research_url(url: str, output_dir: str = None):
    print(f"🌐 Investigating URL: {url}")
    req("/api/navigate", method="POST", data={"url": url})
    
    print("⏳ Waiting for page load and network settlement (6s)...")
    time.sleep(6)

    status = req("/api/status")
    print(f"✓ Title: {status.get('pageTitle')}")

    # 1. Extract clean text
    dom_text = req("/api/dom?format=text")
    clean_text = dom_text.get("text", "")

    # 2. Extract interactive elements
    dom_interactive = req("/api/dom?format=interactive")
    elements = dom_interactive.get("elements", [])

    # 3. Extract network traffic
    traffic = req("/api/traffic?limit=50")

    # 4. Screenshot
    screenshot = req("/api/screenshot", method="POST")

    report = {
        "url": url,
        "title": status.get("pageTitle"),
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "screenshotPath": screenshot.get("path"),
        "totalInteractiveElements": len(elements),
        "totalNetworkRequests": len(traffic),
        "textLength": len(clean_text),
        "interactiveElementsSample": elements[:20],
        "networkRequests": traffic,
        "textPreview": clean_text[:2000]
    }

    out_folder = output_dir or os.path.expanduser("~/storage/documents/BrowserBridge")
    if not os.path.exists(out_folder):
        os.makedirs(out_folder, exist_ok=True)

    report_file = os.path.join(out_folder, f"research_{int(time.time())}.json")
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print(f"📊 Deep Research Report saved to: {report_file}")
    if screenshot.get("path"):
        print(f"📸 Screenshot saved to: {screenshot.get('path')}")
    print(f"📝 Text length: {len(clean_text)} chars | API requests captured: {len(traffic)}")

def main():
    parser = argparse.ArgumentParser(description="Web Research Helper")
    parser.add_argument("url", help="Target URL to research")
    parser.add_argument("--output", help="Custom output directory")
    args = parser.parse_args()

    research_url(args.url, args.output)

if __name__ == "__main__":
    main()
