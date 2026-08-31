#!/usr/bin/env python3
"""
Google Flow MCP - Termux CLI Controller (v2.4)
Supports Nano Banana 2, Veo 3.1, Aspect Ratios, Multi-Outputs, Cookie Injection, and Live DOM Dumping.
"""

import sys
import os
import json
import argparse
import requests
from typing import Optional

BASE_URL = "http://127.0.0.1:8765"

def check_status():
    try:
        res = requests.get(f"{BASE_URL}/api/status", timeout=5)
        data = res.json()
        print("=== Google Flow MCP Status (v2.4) ===")
        print(f"Status:            {data.get('status')}")
        print(f"Auth State:        {'✓ Logged In (Ready)' if data.get('isLoggedIn') else '⚠️ Needs Login'}")
        print(f"Supported Models:  {', '.join(data.get('supportedModels', []))}")
        print(f"Aspect Ratios:     {', '.join(data.get('supportedAspectRatios', []))}")
        print(f"Max Batch Outputs: {data.get('maxOutputsCount', 4)}x")
        print(f"Current URL:       {data.get('currentUrl')}")
        print(f"Endpoint:          {BASE_URL}/sse")
    except Exception as e:
        print(f"❌ Error connecting to Flow Android App: {e}")
        print("Make sure Google Flow MCP app is running on your phone.")

def dump_dom(output_file: Optional[str] = None):
    try:
        res = requests.get(f"{BASE_URL}/api/dom-dump", timeout=8)
        data = res.json()
        target_path = output_file or "flow_dom_dump.json"
        with open(target_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"✓ Full Live DOM Dump saved to: {os.path.abspath(target_path)}")
        print(f"Page Title:   {data.get('title')}")
        print(f"Page URL:     {data.get('url')}")
        print(f"Total Buttons: {data.get('totalButtons')}")
        print(f"Total Inputs:  {data.get('totalInputs')}")
    except Exception as e:
        print(f"❌ Error dumping DOM: {e}")

def import_cookies(cookies: str):
    try:
        res = requests.post(f"{BASE_URL}/api/cookies", json={"cookies": cookies}, timeout=5)
        data = res.json()
        if data.get("success"):
            print("✓ Cookies successfully injected into Android App WebView!")
            print("Flow page reloaded with authenticated session.")
        else:
            print(f"❌ Failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Error importing cookies: {e}")

def list_projects():
    try:
        res = requests.get(f"{BASE_URL}/api/projects", timeout=5)
        projects = res.json()
        print(f"=== Flow Projects ({len(projects)}) ===")
        for p in projects:
            print(f"• {p.get('name')} (ID: {p.get('id')})")
    except Exception as e:
        print(f"❌ Error listing projects: {e}")

def create_project(name: str):
    try:
        res = requests.post(f"{BASE_URL}/api/projects", json={"name": name}, timeout=5)
        print(f"✓ Project creation requested: {name}")
    except Exception as e:
        print(f"❌ Error creating project: {e}")

def generate_image(prompt: str, model: str = "nano-banana-2", aspect_ratio: str = "1:1", count: int = 1, output_path: Optional[str] = None):
    print(f"🎨 Generating image [{model}] ({aspect_ratio}, {count}x): '{prompt}'...")
    try:
        payload = {
            "prompt": prompt,
            "model": model,
            "aspectRatio": aspect_ratio,
            "count": count
        }
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)
        
        res = requests.post(f"{BASE_URL}/api/generate", json=payload, timeout=240)
        data = res.json()
        if data.get("success"):
            print(f"✓ Image generated successfully with {data.get('model')} ({data.get('aspectRatio')})!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def generate_with_reference(prompt: str, image_path: str, model: str = "nano-banana-2", aspect_ratio: str = "1:1", count: int = 1, output_path: Optional[str] = None):
    abs_image = os.path.abspath(image_path)
    if not os.path.exists(abs_image):
        print(f"❌ Reference image not found: {abs_image}")
        return

    print(f"🖼️ Generating with reference '{abs_image}' [{model}] ({aspect_ratio}, {count}x): '{prompt}'...")
    try:
        payload = {
            "prompt": prompt,
            "imagePath": abs_image,
            "model": model,
            "aspectRatio": aspect_ratio,
            "count": count
        }
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)

        res = requests.post(f"{BASE_URL}/api/generate-with-reference", json=payload, timeout=260)
        data = res.json()
        if data.get("success"):
            print(f"✓ Output generated successfully!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def generate_video(prompt: str, model: str = "veo-3.1", aspect_ratio: str = "16:9", output_path: Optional[str] = None):
    print(f"🎬 Generating video [{model}] ({aspect_ratio}): '{prompt}'...")
    try:
        payload = {
            "prompt": prompt,
            "model": model,
            "aspectRatio": aspect_ratio
        }
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)

        res = requests.post(f"{BASE_URL}/api/video", json=payload, timeout=400)
        data = res.json()
        if data.get("success"):
            print(f"✓ Video generated successfully with {model}!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Video generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def main():
    parser = argparse.ArgumentParser(description="Google Flow Android MCP CLI Controller (v2.4)")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # status
    subparsers.add_parser("status", help="Check Flow MCP app and login status")

    # dump-dom
    dom_parser = subparsers.add_parser("dump-dom", help="Inspect and dump current real-time DOM structure")
    dom_parser.add_argument("--output", "-o", help="Target JSON file path")

    # cookies
    cookie_parser = subparsers.add_parser("import-cookies", help="Inject session cookies into Android App")
    cookie_parser.add_argument("cookies", help="Cookie string (e.g. 'SID=...; SSID=...')")

    # projects
    subparsers.add_parser("projects", help="List user projects in Flow")
    proj_create = subparsers.add_parser("create-project", help="Create a new project workspace")
    proj_create.add_argument("--name", "-n", required=True, help="Project name")

    # image
    img_parser = subparsers.add_parser("image", help="Generate AI Image with Nano Banana 2")
    img_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    img_parser.add_argument("--model", "-m", default="nano-banana-2", choices=["nano-banana-2", "nano-banana"], help="Image model")
    img_parser.add_argument("--ratio", "-r", default="1:1", choices=["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"], help="Aspect ratio")
    img_parser.add_argument("--count", "-c", type=int, default=1, choices=[1, 2, 3, 4], help="Outputs count")
    img_parser.add_argument("--output", "-o", help="Destination file path")

    # ref-image
    ref_parser = subparsers.add_parser("ref-image", help="Generate AI Image with reference image")
    ref_parser.add_argument("--image", "-i", required=True, help="Path to reference image")
    ref_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    ref_parser.add_argument("--model", "-m", default="nano-banana-2", choices=["nano-banana-2", "nano-banana"], help="Image model")
    ref_parser.add_argument("--ratio", "-r", default="1:1", choices=["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"], help="Aspect ratio")
    ref_parser.add_argument("--count", "-c", type=int, default=1, choices=[1, 2, 3, 4], help="Outputs count")
    ref_parser.add_argument("--output", "-o", help="Destination file path")

    # video
    vid_parser = subparsers.add_parser("video", help="Generate AI Video (Veo 3.1)")
    vid_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    vid_parser.add_argument("--model", "-m", default="veo-3.1", choices=["veo-3.1"], help="Video model")
    vid_parser.add_argument("--ratio", "-r", default="16:9", choices=["16:9", "9:16"], help="Aspect ratio")
    vid_parser.add_argument("--output", "-o", help="Destination MP4 file path")

    args = parser.parse_args()

    if args.command == "status":
        check_status()
    elif args.command == "dump-dom":
        dump_dom(args.output)
    elif args.command == "import-cookies":
        import_cookies(args.cookies)
    elif args.command == "projects":
        list_projects()
    elif args.command == "create-project":
        create_project(args.name)
    elif args.command == "image":
        generate_image(args.prompt, args.model, args.ratio, args.count, args.output)
    elif args.command == "ref-image":
        generate_with_reference(args.prompt, args.image, args.model, args.ratio, args.count, args.output)
    elif args.command == "video":
        generate_video(args.prompt, args.model, args.ratio, args.output)

if __name__ == "__main__":
    main()
