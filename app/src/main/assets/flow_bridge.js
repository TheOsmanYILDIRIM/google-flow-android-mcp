/**
 * Google Flow Android MCP Bridge (v3.7 - True Lexical AST & Paste Command Engine)
 * Fully integrates with Lexical RichText framework to trigger real generation requests.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge v3.7] Initializing True Lexical Engine...");

    let baselineUuids = [];

    function captureBaselineUuids() {
        const set = new Set();
        document.querySelectorAll('img, video').forEach(el => {
            const src = el.src || el.currentSrc || el.getAttribute('poster') || '';
            const match = src.match(/media\.getMediaUrlRedirect\?name=([a-f0-9-]+)/);
            if (match) set.add(match[1]);
        });
        baselineUuids = Array.from(set);
        console.log("[FlowBridge] Baseline media captured:", baselineUuids.length);
    }

    function writeToLexicalEditor(promptDiv, text) {
        if (!promptDiv) return false;

        promptDiv.focus();

        // 1. Dispatch synthetic Paste Event (Lexical PASTE_COMMAND)
        try {
            const dt = new DataTransfer();
            dt.setData('text/plain', text);
            const pasteEvt = new ClipboardEvent('paste', {
                bubbles: true,
                cancelable: true,
                clipboardData: dt
            });
            promptDiv.dispatchEvent(pasteEvt);
            console.log("[FlowBridge] Dispatched ClipboardEvent('paste')");
        } catch (e) {
            console.warn("[FlowBridge] Paste event error:", e);
        }

        // 2. Range & document.execCommand('insertText')
        try {
            const sel = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(promptDiv);
            sel.removeAllRanges();
            sel.addRange(range);
            document.execCommand('insertText', false, text);
            console.log("[FlowBridge] Executed document.execCommand('insertText')");
        } catch (e) {}

        // 3. Fallback: Direct DOM & InputEvents
        if (!promptDiv.textContent.includes(text)) {
            promptDiv.innerHTML = `<p>${text}</p>`;
        }

        try {
            promptDiv.dispatchEvent(new InputEvent('beforeinput', {
                bubbles: true,
                cancelable: true,
                inputType: 'insertText',
                data: text
            }));
            promptDiv.dispatchEvent(new InputEvent('input', {
                bubbles: true,
                cancelable: true,
                inputType: 'insertText',
                data: text
            }));
        } catch (e) {}

        promptDiv.dispatchEvent(new Event('input', { bubbles: true }));
        promptDiv.dispatchEvent(new Event('change', { bubbles: true }));

        return true;
    }

    function triggerSubmitAction() {
        const buttons = Array.from(document.querySelectorAll('button'));
        
        // Priority 1: Arrow forward submit button
        let submitBtn = buttons.find(b => (b.textContent || '').includes('arrow_forward'));
        
        // Priority 2: Oluştur / Generate
        if (!submitBtn) {
            submitBtn = buttons.find(b => {
                const t = (b.textContent || '').trim();
                const cls = (b.className || '');
                return t.includes('Oluştur') || t.includes('Generate') || cls.includes('kmC');
            });
        }

        if (submitBtn) {
            console.log("[FlowBridge] Triggering submit button:", submitBtn.textContent);
            submitBtn.removeAttribute('disabled');
            submitBtn.disabled = false;
            submitBtn.setAttribute('aria-disabled', 'false');

            // Dispatch full pointer and mouse sequence
            ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
                submitBtn.dispatchEvent(new MouseEvent(evt, {
                    bubbles: true,
                    cancelable: true,
                    view: window,
                    buttons: 1
                }));
            });

            try { submitBtn.click(); } catch(e) {}
            if (window.AndroidBridge) window.AndroidBridge.log("✓ Clicked submit button: " + submitBtn.textContent);
            return true;
        }

        // Priority 3: Dispatched Enter Key
        const promptDiv = document.querySelector('div[contenteditable="true"], textarea');
        if (promptDiv) {
            ['keydown', 'keypress', 'keyup'].forEach(evt => {
                promptDiv.dispatchEvent(new KeyboardEvent(evt, {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    ctrlKey: true,
                    bubbles: true,
                    cancelable: true
                }));
            });
        }
        return false;
    }

    function autoApproveDialogs() {
        const buttons = Array.from(document.querySelectorAll('button'));
        const confirmBtn = buttons.find(b => {
            const t = (b.textContent || '').trim().toLowerCase();
            return t.includes('onayla') || t.includes('kabul') || t.includes('approve') || t.includes('accepter');
        });
        if (confirmBtn) {
            console.log("[FlowBridge] Auto-approving credit/generation dialog:", confirmBtn.textContent);
            confirmBtn.click();
            return true;
        }
        return false;
    }

    function pollForOutput(taskId, mediaType, timeoutMs) {
        const startTime = Date.now();
        const checkInterval = setInterval(() => {
            autoApproveDialogs();

            const foundUuids = [];
            document.querySelectorAll('img, video').forEach(el => {
                const src = el.src || el.currentSrc || el.getAttribute('poster') || '';
                const match = src.match(/media\.getMediaUrlRedirect\?name=([a-f0-9-]+)/);
                if (match) {
                    const uuid = match[1];
                    if (!baselineUuids.includes(uuid) && !foundUuids.includes(uuid)) {
                        foundUuids.push(uuid);
                    }
                }
            });

            if (foundUuids.length > 0) {
                clearInterval(checkInterval);
                const mediaUrl = `https://labs.google/fx/api/trpc/media.getMediaUrlRedirect?name=${foundUuids[0]}`;
                console.log("[FlowBridge] New Media UUID Detected:", foundUuids[0]);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onGenerationCompleted(taskId, mediaUrl, JSON.stringify({
                        uuids: foundUuids,
                        count: foundUuids.length,
                        mediaType: mediaType,
                        completedAt: Date.now()
                    }));
                }
                return;
            }

            if (Date.now() - startTime > timeoutMs) {
                clearInterval(checkInterval);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, "Timeout waiting for Flow media output");
                }
            }
        }, 1500);
    }

    window.FlowAutomation = {
        checkAuth: function() {
            const currentUrl = window.location.href;
            const isLoggedIn = currentUrl.includes('/project/') || (!currentUrl.includes('accounts.google.com') && !currentUrl.includes('ServiceLogin'));
            if (window.AndroidBridge && window.AndroidBridge.onAuthStatus) {
                window.AndroidBridge.onAuthStatus(isLoggedIn, currentUrl);
            }
            return isLoggedIn;
        },

        getAccountInfo: function() {
            return JSON.stringify({
                url: window.location.href,
                isLoggedIn: this.checkAuth(),
                credits: "Active",
                supportedModels: ["Nano Banana 2", "Nano Banana", "Veo 3.1 - Fast", "Veo 3.1 - Quality"],
                supportedAspectRatios: ["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"],
                maxOutputsCount: 4,
                timestamp: Date.now()
            });
        },

        dumpFullDom: function() {
            try {
                const buttons = Array.from(document.querySelectorAll('button, div[role="button"], a[role="button"]')).map((b, i) => ({
                    index: i,
                    tag: b.tagName,
                    text: (b.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 100),
                    ariaLabel: b.getAttribute('aria-label') || '',
                    dataTestId: b.getAttribute('data-testid') || '',
                    disabled: b.disabled || b.getAttribute('aria-disabled') === 'true',
                    className: (b.className || '').toString().slice(0, 80)
                }));

                const inputs = Array.from(document.querySelectorAll('textarea, input, div[contenteditable="true"], div[role="textbox"]')).map((inp, i) => ({
                    index: i,
                    tag: inp.tagName,
                    type: inp.getAttribute('type') || '',
                    placeholder: inp.getAttribute('placeholder') || '',
                    value: inp.value || inp.textContent || '',
                    ariaLabel: inp.getAttribute('aria-label') || '',
                    dataTestId: inp.getAttribute('data-testid') || '',
                    isContentEditable: inp.isContentEditable
                }));

                const media = Array.from(document.querySelectorAll('img, video, a[download]')).map((m, i) => ({
                    index: i,
                    tag: m.tagName,
                    src: (m.src || m.href || '').slice(0, 150),
                    alt: m.getAttribute('alt') || ''
                }));

                const dumpObj = {
                    timestamp: new Date().toISOString(),
                    url: window.location.href,
                    title: document.title,
                    totalButtons: buttons.length,
                    buttons: buttons,
                    totalInputs: inputs.length,
                    inputs: inputs,
                    totalMedia: media.length,
                    media: media
                };

                return JSON.stringify(dumpObj, null, 2);
            } catch (err) {
                return JSON.stringify({ error: err.toString() });
            }
        },

        generateImage: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateImage task:", taskId, prompt);
            try {
                captureBaselineUuids();

                // Close any modals
                const closeBtns = Array.from(document.querySelectorAll('button')).filter(b => (b.textContent || '').includes('Kapat') || (b.textContent || '').includes('close'));
                closeBtns.forEach(b => b.click());

                const promptDiv = Array.from(document.querySelectorAll('div[contenteditable="true"]')).find(d => 
                    d.isContentEditable || (d.textContent && (d.textContent.includes('Ne oluşturmak') || d.textContent.includes('want to create')))
                ) || document.querySelector('div[contenteditable="true"], textarea');

                if (!promptDiv) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Prompt box not found");
                    return false;
                }

                writeToLexicalEditor(promptDiv, prompt);

                setTimeout(() => { triggerSubmitAction(); }, 300);
                setTimeout(() => { triggerSubmitAction(); }, 800);
                setTimeout(() => {
                    triggerSubmitAction();
                    pollForOutput(taskId, 'image', 240000);
                }, 1400);

                return true;
            } catch (err) {
                if (window.AndroidBridge) window.AndroidBridge.onError(taskId, err.toString());
                return false;
            }
        },

        generateVideo: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateVideo task:", taskId, prompt);
            try {
                captureBaselineUuids();

                const closeBtns = Array.from(document.querySelectorAll('button')).filter(b => (b.textContent || '').includes('Kapat') || (b.textContent || '').includes('close'));
                closeBtns.forEach(b => b.click());

                const promptDiv = document.querySelector('div[contenteditable="true"], textarea');
                if (!promptDiv) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Prompt box not found");
                    return false;
                }

                writeToLexicalEditor(promptDiv, prompt);

                setTimeout(() => { triggerSubmitAction(); }, 300);
                setTimeout(() => { triggerSubmitAction(); }, 800);
                setTimeout(() => {
                    triggerSubmitAction();
                    pollForOutput(taskId, 'video', 480000);
                }, 1400);

                return true;
            } catch (err) {
                if (window.AndroidBridge) window.AndroidBridge.onError(taskId, err.toString());
                return false;
            }
        }
    };

    setInterval(() => {
        window.FlowAutomation.checkAuth();
    }, 5000);

    console.log("[FlowBridge v3.7] Ready.");
})();
