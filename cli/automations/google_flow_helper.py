#!/usr/bin/env python3
"""
Google Flow Visual & Video Generation Helper (v4.0)
Automates prompt entry, model selection, trigger generation and media download tracking via Android Bridge.
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

def generate_in_flow(prompt: str, model: str = "nano-banana-2", aspect_ratio: str = "1:1"):
    print(f"🌊 Google Flow Automation: '{prompt}'")
    print(f"⚙️ Model: {model} | Aspect Ratio: {aspect_ratio}")

    # Check if we are on Flow URL
    status = req("/api/status")
    if "labs.google" not in (status.get("currentUrl") or ""):
        print("🚀 Navigating to Google Flow...")
        req("/api/navigate", method="POST", data={"url": "https://labs.google/fx/tools/flow"})
        time.sleep(5)

    # Injected generation script
    safe_prompt = prompt.replace("\"", "\\\"").replace("\n", " ")
    js_code = f"""
    (function() {{
        if (window.FlowAutomation && typeof window.FlowAutomation.generateImage === 'function') {{
            return window.FlowAutomation.generateImage('helper_{int(time.time())}', "{safe_prompt}", '{{"model":"{model}","aspectRatio":"{aspect_ratio}"}}');
        }}
        // Fallback: Use universal DOM interactive input
        const promptInput = document.querySelector('[contenteditable="true"], textarea, input[type="text"]');
        if (promptInput) {{
            window.__AGY_BROWSER__.type('[contenteditable="true"], textarea', "{safe_prompt}", true, false);
            setTimeout(() => {{
                window.__AGY_BROWSER__.click('button[type="submit"], button.kmC, button:has(svg)');
            }}, 500);
            return "Submitted via Universal DOM fallback";
        }}
        return "No editor element found";
    }})();
    """

    res = req("/api/eval", method="POST", data={"script": js_code})
    print(f"✓ Triggered generation: {res}")

    print("⏳ Monitoring network traffic for generated media URLs (30s)...")
    for i in range(10):
        time.sleep(3)
        traffic = req("/api/traffic?filter=media.getMediaUrlRedirect&limit=5")
        if traffic:
            print(f"🎉 Captured Generated Media URL: {traffic[0].get('url')}")
            return traffic[0].get("url")

    print("✓ Generation dispatched. Check the app screen or Download/GoogleFlow.")

def main():
    parser = argparse.ArgumentParser(description="Google Flow Generation Helper")
    parser.add_argument("prompt", help="Prompt to generate")
    parser.add_argument("--model", default="nano-banana-2", help="Model name")
    parser.add_argument("--ratio", default="1:1", help="Aspect ratio (1:1, 16:9, 9:16)")
    args = parser.parse_args()

    generate_in_flow(args.prompt, args.model, args.ratio)

if __name__ == "__main__":
    main()
