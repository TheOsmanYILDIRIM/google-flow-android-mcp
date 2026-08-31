#!/usr/bin/env python3
"""
Google Flow Professional Python Scraper & Crawler
Extracts live cookies from the Android app, crawls projects, inspects DOM,
and interacts with Google Flow's tRPC and web APIs.
"""

import sys
import os
import json
import time
import requests
from bs4 import BeautifulSoup
from typing import Optional, Dict, List

MCP_BASE_URL = "http://127.0.0.1:8765"
FLOW_BASE_URL = "https://labs.google/fx/tools/flow"

class GoogleFlowScraper:
    def __init__(self, mcp_url: str = MCP_BASE_URL):
        self.mcp_url = mcp_url
        self.session = requests.Session()
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Ch-Ua": '"Chromium";v="128", "Not;A=Brand";v="24", "Google Chrome";v="128"',
            "Sec-Ch-Ua-Mobile": "?0",
            "Sec-Ch-Ua-Platform": '"Windows"',
            "Sec-Fetch-Dest": "document",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-Site": "same-origin"
        }
        self.session.headers.update(self.headers)
        self.cookie_str = ""

    def sync_cookies_from_app(self) -> bool:
        """Fetches active session cookies from the Android App WebView."""
        try:
            print("🔄 Fetching session cookies from Google Flow MCP Android App...")
            res = requests.get(f"{self.mcp_url}/api/cookies", timeout=5)
            data = res.json()
            if data.get("success") and data.get("cookieHeader"):
                self.cookie_str = data.get("cookieHeader")
                self.session.headers.update({"Cookie": self.cookie_str})
                print(f"✓ Successfully extracted {len(self.cookie_str.split(';'))} cookies from Android App!")
                return True
            else:
                print("⚠️ Android App returned empty cookies. Please ensure you are logged into Google in the app.")
                return False
        except Exception as e:
            print(f"❌ Could not connect to Android App: {e}")
            print("Make sure Google Flow MCP app is running on 127.0.0.1:8765.")
            return False

    def eval_js_in_webview(self, script: str) -> str:
        """Executes JavaScript in the Android WebView directly."""
        try:
            res = requests.post(f"{self.mcp_url}/api/eval", json={"script": script}, timeout=10)
            return res.text
        except Exception as e:
            return json.dumps({"error": str(e)})

    def navigate_webview(self, url: str) -> bool:
        """Navigates the Android WebView to a target URL."""
        try:
            res = requests.post(f"{self.mcp_url}/api/navigate", json={"url": url}, timeout=5)
            return res.json().get("success", False)
        except Exception as e:
            print(f"❌ Navigate error: {e}")
            return False

    def get_webview_html(self) -> str:
        """Extracts complete live outerHTML from the Android WebView."""
        try:
            res = requests.get(f"{self.mcp_url}/api/page-source", timeout=10)
            return res.text
        except Exception as e:
            print(f"❌ Error getting page source: {e}")
            return ""

    def inspect_and_crawl_current_page(self) -> Dict:
        """Parses the current WebView HTML with BeautifulSoup."""
        html = self.get_webview_html()
        if not html:
            print("❌ No HTML received from WebView.")
            return {}

        soup = BeautifulSoup(html, "html.parser")
        title = soup.title.string if soup.title else "No Title"

        buttons = []
        for i, btn in enumerate(soup.find_all("button")):
            buttons.append({
                "index": i,
                "text": btn.get_text(strip=True),
                "aria_label": btn.get("aria-label", ""),
                "disabled": btn.has_attr("disabled"),
                "classes": btn.get("class", [])
            })

        inputs = []
        for i, inp in enumerate(soup.find_all(["input", "textarea", "div"])):
            if inp.name in ["input", "textarea"] or inp.get("contenteditable") == "true":
                inputs.append({
                    "index": i,
                    "tag": inp.name,
                    "type": inp.get("type", ""),
                    "placeholder": inp.get("placeholder", ""),
                    "text": inp.get_text(strip=True),
                    "is_contenteditable": inp.get("contenteditable") == "true"
                })

        media = []
        for img in soup.find_all("img"):
            src = img.get("src", "")
            if "media.getMediaUrlRedirect" in src or "googleusercontent" in src:
                media.append({
                    "type": "image",
                    "src": src,
                    "alt": img.get("alt", "")
                })

        for vid in soup.find_all("video"):
            src = vid.get("src", "")
            media.append({
                "type": "video",
                "src": src
            })

        result = {
            "title": title,
            "total_buttons": len(buttons),
            "buttons": buttons,
            "total_inputs": len(inputs),
            "inputs": inputs,
            "media": media
        }

        print(f"=== Page Analysis: '{title}' ===")
        print(f"• Total Buttons: {len(buttons)}")
        print(f"• Total Inputs:  {len(inputs)}")
        print(f"• Total Media:   {len(media)}")
        return result

    def download_media_direct(self, media_url: str, output_path: str) -> bool:
        """Downloads media directly using authenticated session cookies."""
        try:
            print(f"⬇️ Downloading: {media_url}")
            res = self.session.get(media_url, timeout=30, stream=True)
            if res.status_code == 200:
                os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
                with open(output_path, "wb") as f:
                    for chunk in res.iter_content(chunk_size=8192):
                        f.write(chunk)
                print(f"✓ Saved to: {output_path} ({os.path.getsize(output_path)} bytes)")
                return True
            else:
                print(f"❌ Failed to download. Status: {res.status_code}")
                return False
        except Exception as e:
            print(f"❌ Download error: {e}")
            return False

def main():
    scraper = GoogleFlowScraper()
    scraper.sync_cookies_from_app()
    scraper.inspect_and_crawl_current_page()

if __name__ == "__main__":
    main()
