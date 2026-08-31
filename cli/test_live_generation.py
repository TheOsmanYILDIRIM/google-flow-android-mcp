#!/usr/bin/env python3
import requests
import time
import os
import json

mcp_url = 'http://127.0.0.1:8765'

print('1. Checking connection to Android App...')
try:
    c_res = requests.get(f'{mcp_url}/api/cookies', timeout=5).json()
    print('✓ Cookies active:', len(c_res.get('cookieHeader', '').split(';')))
except Exception as e:
    print('❌ Connection error:', e)
    exit(1)

# Step 1: Navigate to existing project
target_project_url = 'https://labs.google/fx/tr/tools/flow/project/5b1a8853-026d-4e46-ab40-ce3315a34155'
print(f'\n2. Navigating into existing project: {target_project_url}...')
requests.post(f'{mcp_url}/api/navigate', json={'url': target_project_url}, timeout=5)

# Wait 4s for canvas to stabilize
time.sleep(4)

# Step 2: Inject prompt and click arrow_forward
prompt_text = 'A majestic cyberpunk samurai warrior in neon rain, cinematic 8k masterpiece'
print(f'\n3. Injecting prompt into Lexical Editor: "{prompt_text}"...')

script = """
(function() {
    // 1. Close any modal / drawer
    const closeBtns = Array.from(document.querySelectorAll('button')).filter(b => (b.textContent || '').includes('Kapat') || (b.textContent || '').includes('close'));
    closeBtns.forEach(b => b.click());

    // 2. Find prompt div
    const div = Array.from(document.querySelectorAll('div[contenteditable="true"]')).find(d => 
        (d.textContent || '').includes('Ne oluşturmak') || (d.textContent || '').includes('want to create') || d.isContentEditable
    ) || document.querySelector('div[contenteditable="true"]');

    if (!div) return { error: 'No prompt div found', url: window.location.href };

    const text = 'A majestic cyberpunk samurai warrior in neon rain, cinematic 8k masterpiece';
    div.focus();

    // Lexical paste command
    try {
        const dt = new DataTransfer();
        dt.setData('text/plain', text);
        const pasteEvt = new ClipboardEvent('paste', {
            bubbles: true,
            cancelable: true,
            clipboardData: dt
        });
        div.dispatchEvent(pasteEvt);
    } catch (e) {}

    // Selection execCommand
    try {
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(div);
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('insertText', false, text);
    } catch(e) {}

    if (!div.textContent.includes(text)) {
        div.innerHTML = '<p>' + text + '</p>';
    }

    div.dispatchEvent(new Event('input', { bubbles: true }));
    div.dispatchEvent(new Event('change', { bubbles: true }));

    // Find submit button
    const btn = Array.from(document.querySelectorAll('button')).find(b => (b.textContent || '').includes('arrow_forward'));
    if (btn) {
        btn.removeAttribute('disabled');
        btn.disabled = false;
        btn.setAttribute('aria-disabled', 'false');
        btn.click();
        return {
            success: true,
            clicked: btn.textContent,
            url: window.location.href,
            timestamp: Date.now()
        };
    }

    return { error: 'arrow_forward button not found', url: window.location.href };
})()
"""

res2 = requests.post(f'{mcp_url}/api/eval', json={'script': script}, timeout=6).json()
print('Trigger Result:', json.dumps(res2, indent=2))
