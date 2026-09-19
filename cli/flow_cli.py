#!/usr/bin/env python3
"""
Universal Android Browser & Google Flow CLI Controller (v4.0)
Full browser control, DOM extraction, JavaScript evaluation, network traffic sniffing, screenshots, and AI generation.
"""

import sys
import os
import json
import argparse
import urllib.request
import urllib.parse
import urllib.error
from typing import Optional

BASE_URL = os.environ.get("BROWSER_BRIDGE_URL", "http://127.0.0.1:8765")

def req(endpoint: str, method: str = "GET", data: dict = None, timeout: int = 20):
    url = f"{BASE_URL}{endpoint}"
    req_data = None
    headers = {"Content-Type": "application/json"}
    if data is not None:
        req_data = json.dumps(data).encode("utf-8")
    
    request = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            content = resp.read().decode("utf-8")
            try:
                return json.loads(content)
            except Exception:
                return {"raw": content}
    except urllib.error.URLError as e:
        print(f"❌ Connection error to Android Bridge ({url}): {e}")
        print("💡 Make sure the Android Browser Bridge app is running.")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)

def check_status():
    data = req("/api/status")
    print("🌐 === Universal Android Browser Bridge (v4.0) ===")
    print(f"Status:            {data.get('status')}")
    print(f"Auth State:        {'✓ Ready / Logged In' if data.get('isLoggedIn') else '⚠️ Needs Login / Fresh'}")
    print(f"Current URL:       {data.get('currentUrl')}")
    print(f"Page Title:        {data.get('pageTitle')}")
    print(f"Traffic Captured:  {data.get('trafficCount')} requests")
    print(f"Console Logs:      {data.get('consoleCount')} lines")
    print(f"Supported Models:  {', '.join(data.get('supportedModels', []))}")
    print(f"Aspect Ratios:     {', '.join(data.get('supportedAspectRatios', []))}")

def main():
    parser = argparse.ArgumentParser(description="Universal Android Browser & Google Flow CLI")
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    subparsers.add_parser("status", help="Check bridge status")

    nav_p = subparsers.add_parser("nav", help="Navigate to URL")
    nav_p.add_argument("url", help="Target URL")

    eval_p = subparsers.add_parser("eval", help="Evaluate JavaScript")
    eval_p.add_argument("script", help="JavaScript expression")

    dom_p = subparsers.add_parser("dom", help="Inspect DOM")
    dom_p.add_argument("--format", choices=["interactive", "text", "html"], default="interactive")
    dom_p.add_argument("--selector", help="CSS selector")

    click_p = subparsers.add_parser("click", help="Click element")
    click_p.add_argument("--selector", help="CSS selector")
    click_p.add_argument("--text", help="Visible text")

    type_p = subparsers.add_parser("type", help="Type text into field")
    type_p.add_argument("--selector", required=True)
    type_p.add_argument("--text", required=True)
    type_p.add_argument("--enter", action="store_true")

    traffic_p = subparsers.add_parser("traffic", help="View network traffic")
    traffic_p.add_argument("--filter", help="URL or method filter")
    traffic_p.add_argument("--limit", type=int, default=30)
    traffic_p.add_argument("--clear", action="store_true")

    console_p = subparsers.add_parser("console", help="View console logs")
    console_p.add_argument("--level", choices=["log", "info", "warn", "error"])
    console_p.add_argument("--limit", type=int, default=30)
    console_p.add_argument("--clear", action="store_true")

    subparsers.add_parser("screenshot", help="Capture screenshot")

    cookies_p = subparsers.add_parser("cookies", help="Manage cookies")
    cookies_p.add_argument("--url", default="https://labs.google")
    cookies_p.add_argument("--set", help="Cookies to import")
    cookies_p.add_argument("--clear", action="store_true")

    # Flow generation commands
    gen_p = subparsers.add_parser("generate", help="Generate AI Image in Flow")
    gen_p.add_argument("prompt", help="Text prompt")
    gen_p.add_argument("--model", default="Nano Banana 2")
    gen_p.add_argument("--ratio", default="1:1")
    gen_p.add_argument("--count", type=int, default=1)

    args = parser.parse_args()

    if not args.command or args.command == "status":
        check_status()
        return

    if args.command == "nav":
        data = req("/api/navigate", method="POST", data={"url": args.url})
        print(f"✓ Navigated to: {args.url}")

    elif args.command == "eval":
        data = req("/api/eval", method="POST", data={"script": args.script})
        print(json.dumps(data, indent=2, ensure_ascii=False))

    elif args.command == "dom":
        params = f"?format={args.format}"
        if args.selector:
            params += f"&selector={urllib.parse.quote(args.selector)}"
        data = req(f"/api/dom{params}")
        if args.format == "interactive":
            elems = data.get("elements", [])
            print(f"📄 === Interactive Elements ({len(elems)}) for '{data.get('title')}' ===")
            for el in elems:
                print(f"[{el.get('index')}] <{el.get('tag')}> {el.get('selector')} | '{el.get('text')}' (val: {el.get('value')})")
        else:
            print(json.dumps(data, indent=2, ensure_ascii=False))

    elif args.command == "click":
        data = req("/api/click", method="POST", data={"selector": args.selector, "text": args.text})
        print("✓ Click:", data)

    elif args.command == "type":
        data = req("/api/type", method="POST", data={"selector": args.selector, "text": args.text, "enter": args.enter})
        print("✓ Type:", data)

    elif args.command == "traffic":
        if args.clear:
            req("/api/traffic/clear", method="POST")
            print("✓ Traffic cleared.")
        else:
            params = f"?limit={args.limit}"
            if args.filter:
                params += f"&filter={urllib.parse.quote(args.filter)}"
            data = req(f"/api/traffic{params}")
            print(f"📡 === Intercepted Traffic ({len(data)}) ===")
            for t in data:
                print(f"[{t.get('method')}] {t.get('status')} {t.get('url')} ({t.get('durationMs')}ms)")
                if t.get('requestBody'):
                    print(f"   Payload: {str(t.get('requestBody'))[:100]}")
                if t.get('responseBody'):
                    print(f"   Response: {str(t.get('responseBody'))[:150]}")

    elif args.command == "console":
        if args.clear:
            req("/api/console/clear", method="POST")
            print("✓ Console cleared.")
        else:
            params = f"?limit={args.limit}"
            if args.level:
                params += f"&level={urllib.parse.quote(args.level)}"
            data = req(f"/api/console{params}")
            print(f"📜 === Console Logs ({len(data)}) ===")
            for c in data:
                print(f"[{c.get('timestamp')}] [{c.get('level').upper()}]: {c.get('message')}")

    elif args.command == "screenshot":
        data = req("/api/screenshot", method="POST")
        if data.get("success"):
            print(f"📸 Screenshot saved to: {data.get('path')}")
        else:
            print(f"❌ Screenshot failed: {data.get('error')}")

    elif args.command == "cookies":
        if args.clear:
            req("/api/cookies/clear", method="POST")
            print("✓ Cookies cleared.")
        elif args.set:
            data = req("/api/cookies", method="POST", data={"cookies": args.set, "url": args.url})
            print(f"✓ Cookies set: {data}")
        else:
            data = req(f"/api/cookies?url={urllib.parse.quote(args.url)}")
            print(f"🍪 Cookies for {args.url}:")
            print(data.get("cookieHeader", ""))

    elif args.command == "generate":
        print(f"🎨 Generating image in Flow: '{args.prompt}'...")
        # triggers Flow generation
        res = req("/api/eval", method="POST", data={"script": f"window.FlowAutomation ? window.FlowAutomation.generateImage('cli', '{args.prompt}', '{{\"model\":\"{args.model}\",\"aspectRatio\":\"{args.ratio}\"}}') : 'Flow automation not ready';"})
        print(f"✓ Result: {res}")

if __name__ == "__main__":
    main()
