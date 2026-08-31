#!/usr/bin/env python3
"""
Google Flow MCP - Termux CLI Controller
Directly communicates with the Flow Android App running at http://127.0.0.1:8765
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
        print("=== Google Flow MCP Status ===")
        print(f"Status:      {data.get('status')}")
        print(f"Auth State:  {'✓ Logged In (Ready)' if data.get('isLoggedIn') else '⚠️ Needs Login'}")
        print(f"Current URL: {data.get('currentUrl')}")
        print(f"Endpoint:    {BASE_URL}/sse")
    except Exception as e:
        print(f"❌ Error connecting to Flow Android App: {e}")
        print("Make sure Google Flow MCP app is running on your phone.")

def generate_image(prompt: str, output_path: Optional[str] = None):
    print(f"🎨 Generating image with prompt: '{prompt}'...")
    try:
        payload = {"prompt": prompt}
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)
        
        res = requests.post(f"{BASE_URL}/api/generate", json=payload, timeout=180)
        data = res.json()
        if data.get("success"):
            print(f"✓ Image generated successfully!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def generate_with_reference(prompt: str, image_path: str, output_path: Optional[str] = None):
    abs_image = os.path.abspath(image_path)
    if not os.path.exists(abs_image):
        print(f"❌ Reference image not found: {abs_image}")
        return

    print(f"🖼️ Generating with reference '{abs_image}' and prompt: '{prompt}'...")
    try:
        payload = {
            "prompt": prompt,
            "imagePath": abs_image
        }
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)

        res = requests.post(f"{BASE_URL}/api/generate-with-reference", json=payload, timeout=200)
        data = res.json()
        if data.get("success"):
            print(f"✓ Output generated successfully!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def generate_video(prompt: str, output_path: Optional[str] = None):
    print(f"🎬 Generating video (Veo) with prompt: '{prompt}'...")
    try:
        payload = {"prompt": prompt}
        if output_path:
            payload["outputPath"] = os.path.abspath(output_path)

        res = requests.post(f"{BASE_URL}/api/video", json=payload, timeout=360)
        data = res.json()
        if data.get("success"):
            print(f"✓ Video generated successfully!")
            print(f"Media URL:  {data.get('mediaUrl')}")
            print(f"Saved to:   {data.get('localPath')}")
        else:
            print(f"❌ Video generation failed: {data.get('error')}")
    except Exception as e:
        print(f"❌ Request error: {e}")

def main():
    parser = argparse.ArgumentParser(description="Google Flow Android MCP CLI Controller")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # status
    subparsers.add_parser("status", help="Check Flow MCP app and login status")

    # image
    img_parser = subparsers.add_parser("image", help="Generate AI Image from prompt")
    img_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    img_parser.add_argument("--output", "-o", help="Destination file path")

    # ref-image
    ref_parser = subparsers.add_parser("ref-image", help="Generate AI Image with reference image")
    ref_parser.add_argument("--image", "-i", required=True, help="Path to reference image")
    ref_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    ref_parser.add_argument("--output", "-o", help="Destination file path")

    # video
    vid_parser = subparsers.add_parser("video", help="Generate AI Video from prompt (Veo)")
    vid_parser.add_argument("--prompt", "-p", required=True, help="Text prompt")
    vid_parser.add_argument("--output", "-o", help="Destination MP4 file path")

    args = parser.parse_args()

    if args.command == "status":
        check_status()
    elif args.command == "image":
        generate_image(args.prompt, args.output)
    elif args.command == "ref-image":
        generate_with_reference(args.prompt, args.image, args.output)
    elif args.command == "video":
        generate_video(args.prompt, args.output)

if __name__ == "__main__":
    main()
