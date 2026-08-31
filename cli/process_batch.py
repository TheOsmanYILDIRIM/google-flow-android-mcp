#!/usr/bin/env python3
"""
Google Flow MCP - Batch Pipeline Runner (v2.0)
Processes folders of images (e.g. 64 PNG files) with Nano Banana 2, selectable aspect ratios (1:1, 16:9, etc.),
multi-output variations, and systematic file naming.
"""

import sys
import os
import time
import argparse
import requests
from pathlib import Path

BASE_URL = "http://127.0.0.1:8765"

def run_batch(
    input_dir: str,
    output_dir: str,
    prompt_template: str,
    model: str = "nano-banana-2",
    aspect_ratio: str = "1:1",
    count: int = 1,
    name_pattern: str = "{name}_stylized{ext}",
    retry_count: int = 2
):
    input_path = Path(input_dir).resolve()
    output_path = Path(output_dir).resolve()
    output_path.mkdir(parents=True, exist_ok=True)

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
    print(f"🧠 Model:            {model}")
    print(f"📐 Aspect Ratio:     {aspect_ratio}")
    print(f"🔢 Outputs Count:    {count}x")
    print(f"✍️  Prompt:           {prompt_template}")
    print("=" * 65)

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
                    "model": model,
                    "aspectRatio": aspect_ratio,
                    "count": count,
                    "outputPath": str(dest_file)
                }
                res = requests.post(f"{BASE_URL}/api/generate-with-reference", json=payload, timeout=240)
                data = res.json()

                if data.get("success"):
                    elapsed = time.time() - start_time
                    print(f" ✓ Done ({elapsed:.1f}s) [{data.get('model')}]")
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

        time.sleep(1.5)

    print("=" * 65)
    print(f"🎉 Batch Pipeline Finished! Total: {total_images} | Success: {successful} | Failed: {failed}")
    print(f"📂 Output folder: {output_path}")

def main():
    parser = argparse.ArgumentParser(description="Google Flow MCP Batch Processing Pipeline (v2.0)")
    parser.add_argument("--input-dir", "-i", required=True, help="Input directory containing PNG images")
    parser.add_argument("--output-dir", "-o", required=True, help="Output directory to save generated images")
    parser.add_argument("--prompt", "-p", required=True, help="Prompt template for generation")
    parser.add_argument("--model", "-m", default="nano-banana-2", choices=["nano-banana-2", "nano-banana"], help="Image model")
    parser.add_argument("--ratio", "-r", default="1:1", choices=["1:1", "16:9", "9:16", "4:3", "3:4"], help="Aspect ratio")
    parser.add_argument("--count", "-c", type=int, default=1, choices=[1, 2, 3, 4], help="Outputs count")
    parser.add_argument("--pattern", default="{name}_stylized{ext}", help="Naming pattern")
    parser.add_argument("--retries", type=int, default=2, help="Number of retries on failure")

    args = parser.parse_args()
    run_batch(args.input_dir, args.output_dir, args.prompt, args.model, args.ratio, args.count, args.pattern, args.retries)

if __name__ == "__main__":
    main()
