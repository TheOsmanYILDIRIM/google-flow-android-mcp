#!/usr/bin/env python3
"""
Google Flow MCP - Batch Pipeline Runner
Takes a folder of images (e.g. 64 PNG files), processes each image through Google Flow
with customizable prompts, and saves results to an output folder with systematic naming.
"""

import sys
import os
import glob
import time
import argparse
import requests
from pathlib import Path

BASE_URL = "http://127.0.0.1:8765"

def run_batch(input_dir: str, output_dir: str, prompt_template: str, name_pattern: str = "{name}_stylized{ext}", retry_count: int = 2):
    input_path = Path(input_dir).resolve()
    output_path = Path(output_dir).resolve()
    output_path.mkdir(parents=True, exist_ok=True)

    # Collect images
    extensions = ("*.png", "*.jpg", "*.jpeg", "*.webp")
    image_files = []
    for ext in extensions:
        image_files.extend(input_path.glob(ext))
    image_files = sorted(image_files)

    total_images = len(image_files)
    if total_images == 0:
        print(f"❌ No images found in: {input_path}")
        return

    print(f"🚀 Starting Batch Pipeline for {total_images} images")
    print(f"📁 Input Directory:  {input_path}")
    print(f"📁 Output Directory: {output_path}")
    print(f"✍️  Prompt:           {prompt_template}")
    print("=" * 60)

    # Check Flow MCP connection
    try:
        res = requests.get(f"{BASE_URL}/api/status", timeout=5)
        status_data = res.json()
        if not status_data.get("isLoggedIn"):
            print("⚠️ WARNING: Flow app reported not logged in! Make sure you signed in via the app.")
    except Exception as e:
        print(f"❌ Cannot connect to Flow Android MCP at {BASE_URL}: {e}")
        print("Please launch the Google Flow MCP app on your phone first.")
        return

    successful = 0
    failed = 0

    for idx, img_file in enumerate(image_files, 1):
        target_name = name_pattern.format(name=img_file.stem, ext=img_file.suffix)
        dest_file = output_path / target_name

        if dest_file.exists():
            print(f"[{idx}/{total_images}] ⏭️  Skipping {img_file.name} (Already exists: {dest_file.name})")
            successful += 1
            continue

        print(f"[{idx}/{total_images}] 🔄 Processing: {img_file.name} -> {target_name} ...", end="", flush=True)

        # Dynamic prompt formatting if template uses {filename}
        formatted_prompt = prompt_template.format(filename=img_file.stem)

        attempts = 0
        success = False
        start_time = time.time()

        while attempts <= retry_count and not success:
            attempts += 1
            try:
                payload = {
                    "prompt": formatted_prompt,
                    "imagePath": str(img_file),
                    "outputPath": str(dest_file)
                }
                res = requests.post(f"{BASE_URL}/api/generate-with-reference", json=payload, timeout=240)
                data = res.json()

                if data.get("success"):
                    elapsed = time.time() - start_time
                    print(f" ✓ Done ({elapsed:.1f}s)")
                    successful += 1
                    success = True
                else:
                    if attempts <= retry_count:
                        print(f" ⚠️ Retry {attempts}/{retry_count} ({data.get('error')}) ...", end="", flush=True)
                        time.sleep(3)
                    else:
                        print(f" ❌ Failed: {data.get('error')}")
                        failed += 1
            except Exception as e:
                if attempts <= retry_count:
                    print(f" ⚠️ Network Retry {attempts}/{retry_count} ...", end="", flush=True)
                    time.sleep(3)
                else:
                    print(f" ❌ Request Error: {e}")
                    failed += 1

        # Small delay between generations to preserve rate limits
        time.sleep(1.5)

    print("=" * 60)
    print(f"🎉 Batch Pipeline Finished! Total: {total_images} | Success: {successful} | Failed: {failed}")
    print(f"📂 Output folder: {output_path}")

def main():
    parser = argparse.ArgumentParser(description="Google Flow MCP Batch Processing Pipeline")
    parser.add_argument("--input-dir", "-i", required=True, help="Input directory containing PNG images")
    parser.add_argument("--output-dir", "-o", required=True, help="Output directory to save generated images")
    parser.add_argument("--prompt", "-p", required=True, help="Prompt template for generation")
    parser.add_argument("--pattern", default="{name}_stylized{ext}", help="Naming pattern (e.g. '{name}_gen{ext}')")
    parser.add_argument("--retries", type=int, default=2, help="Number of retries on failure")

    args = parser.parse_args()
    run_batch(args.input_dir, args.output_dir, args.prompt, args.pattern, args.retries)

if __name__ == "__main__":
    main()
