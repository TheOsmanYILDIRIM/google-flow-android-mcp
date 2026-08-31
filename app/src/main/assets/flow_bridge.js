/**
 * Google Flow & FX Suite Automation Bridge & Scraper (v2.6)
 * Complete Lexical/Slate/React Contenteditable Engine & Multi-Event Dispatcher.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge] Initializing Google Flow Bridge v2.6 (Rich Text & Full Event Trigger)...");

    function simulateUserTyping(element, text) {
        element.focus();

        // 1. Mouse/Pointer focus sequence
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
            element.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));
        });

        // 2. Clear content
        if (element.isContentEditable || element.getAttribute('contenteditable') === 'true') {
            const selection = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(element);
            selection.removeAllRanges();
            selection.addRange(range);
            document.execCommand('delete', false, null);

            // 3. Dispatch beforeinput (Lexical / Slate / React 18+ requirement)
            try {
                const beforeInput = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text
                });
                element.dispatchEvent(beforeInput);
            } catch (e) {}

            // 4. ExecCommand insertText
            document.execCommand('insertText', false, text);

            if (!element.textContent || !element.textContent.includes(text)) {
                element.textContent = text;
            }

            // 5. Input and change events
            try {
                const inputEvent = new InputEvent('input', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text
                });
                element.dispatchEvent(inputEvent);
            } catch (e) {
                element.dispatchEvent(new Event('input', { bubbles: true }));
            }
            element.dispatchEvent(new Event('change', { bubbles: true }));

            // 6. Character-level keystrokes to ensure validation triggers
            element.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', keyCode: 65, which: 65, bubbles: true }));
            element.dispatchEvent(new KeyboardEvent('keypress', { key: 'a', code: 'KeyA', keyCode: 65, which: 65, bubbles: true }));
            element.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', code: 'KeyA', keyCode: 65, which: 65, bubbles: true }));
            
            // Backspace the dummy key if needed
            element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true }));
            element.dispatchEvent(new KeyboardEvent('keyup', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true }));
        } else {
            const proto = element.tagName.toLowerCase() === 'textarea'
                ? window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement.prototype;
            const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
            if (nativeSetter) nativeSetter.call(element, text);
            else element.value = text;

            element.dispatchEvent(new Event('input', { bubbles: true }));
            element.dispatchEvent(new Event('change', { bubbles: true }));
        }
    }

    function forceTriggerClick(button) {
        if (!button) return false;
        
        // Remove disabled flags
        button.removeAttribute('disabled');
        button.disabled = false;
        button.setAttribute('aria-disabled', 'false');

        // Full mouse/touch event chain
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
            button.dispatchEvent(new MouseEvent(evt, {
                bubbles: true,
                cancelable: true,
                view: window,
                buttons: 1
            }));
        });

        // Also call native .click()
        try {
            button.click();
        } catch (e) {}

        return true;
    }

    function triggerGenerateAction() {
        const buttons = Array.from(document.querySelectorAll('button'));
        
        // Match button containing "arrow_forward" or "Oluştur" or "Generate" or "Create"
        const candidates = buttons.filter(b => {
            const txt = (b.textContent || '').trim();
            const cls = (b.className || '');
            return txt.includes('arrow_forward') || txt.includes('Oluştur') || txt.includes('Generate') || txt.includes('Create') || cls.includes('kmC') || cls.includes('joS');
        });

        console.log("[FlowBridge] Found candidate generate buttons:", candidates.length);

        for (const btn of candidates) {
            console.log("[FlowBridge] Triggering candidate button:", btn.textContent);
            forceTriggerClick(btn);
        }

        // Also trigger Enter key on prompt input
        const promptEl = document.querySelector('div[contenteditable="true"], textarea');
        if (promptEl) {
            ['keydown', 'keypress', 'keyup'].forEach(evt => {
                promptEl.dispatchEvent(new KeyboardEvent(evt, {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    ctrlKey: true,
                    bubbles: true,
                    cancelable: true
                }));
                promptEl.dispatchEvent(new KeyboardEvent(evt, {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true
                }));
            });
        }
    }

    function applyModelSettings(model, aspectRatio, count) {
        const settingsBtn = Array.from(document.querySelectorAll('button')).find(b => {
            const txt = (b.textContent || '');
            return txt.includes('Nano Banana') || txt.includes('Veo') || txt.includes('🍌') || txt.includes('crop_square');
        });

        if (settingsBtn) {
            forceTriggerClick(settingsBtn);
            setTimeout(() => {
                const options = Array.from(document.querySelectorAll('button, div[role="menuitem"], div[role="radio"], span'));
                
                if (model) {
                    const mOption = options.find(o => o.textContent && o.textContent.toLowerCase().includes(model.toLowerCase()));
                    if (mOption) forceTriggerClick(mOption);
                }

                if (aspectRatio) {
                    const rOption = options.find(o => o.textContent && o.textContent.includes(aspectRatio));
                    if (rOption) forceTriggerClick(rOption);
                }

                if (count && count > 1) {
                    const cOption = options.find(o => o.textContent && o.textContent.includes(`x${count}`));
                    if (cOption) forceTriggerClick(cOption);
                }

                document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
            }, 300);
        }
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
                supportedModels: ["nano-banana-2", "nano-banana", "veo-3.1"],
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

                const jsonStr = JSON.stringify(dumpObj, null, 2);
                if (window.AndroidBridge && window.AndroidBridge.log) {
                    window.AndroidBridge.log("=== DOM DUMP READY (" + buttons.length + " buttons, " + inputs.length + " inputs) ===");
                }
                return jsonStr;
            } catch (err) {
                console.error("[FlowBridge] dumpFullDom error:", err);
                return JSON.stringify({ error: err.toString() });
            }
        },

        generateImage: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateImage task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "nano-banana-2";
                const aspectRatio = options.aspectRatio || "1:1";
                const count = options.count || 1;

                applyModelSettings(model, aspectRatio, count);

                const promptDiv = Array.from(document.querySelectorAll('div[contenteditable="true"]')).find(d => 
                    d.isContentEditable || (d.textContent && (d.textContent.includes('Ne oluşturmak') || d.textContent.includes('want to create')))
                ) || document.querySelector('div[contenteditable="true"], textarea');

                if (!promptDiv) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Could not find prompt contenteditable box on page: " + window.location.href);
                    }
                    return false;
                }

                simulateUserTyping(promptDiv, prompt);

                // Multiple timed trigger attempts to ensure React state commits
                setTimeout(() => { triggerGenerateAction(); }, 300);
                setTimeout(() => { triggerGenerateAction(); }, 700);
                setTimeout(() => {
                    triggerGenerateAction();
                    this.watchForOutput(taskId, 'image', count, 200000);
                }, 1200);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateImage error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        generateVideo: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateVideo task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "veo-3.1";
                const aspectRatio = options.aspectRatio || "16:9";

                applyModelSettings(model, aspectRatio, 1);

                const promptDiv = document.querySelector('div[contenteditable="true"], textarea');
                if (!promptDiv) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Could not find prompt input");
                    return false;
                }

                simulateUserTyping(promptDiv, prompt);

                setTimeout(() => { triggerGenerateAction(); }, 300);
                setTimeout(() => {
                    triggerGenerateAction();
                    this.watchForOutput(taskId, 'video', 1, 400000);
                }, 800);

                return true;
            } catch (err) {
                if (window.AndroidBridge) window.AndroidBridge.onError(taskId, err.toString());
                return false;
            }
        },

        watchForOutput: function(taskId, mediaType, expectedCount, timeoutMs) {
            const startTime = Date.now();
            let completed = false;
            expectedCount = expectedCount || 1;

            const existingMedia = Array.from(document.querySelectorAll('img[src*="media.getMediaUrlRedirect"], video[src*="media.getMediaUrlRedirect"]'))
                .map(m => m.src);

            const observer = new MutationObserver((mutations, obs) => {
                if (completed) return;

                const mediaElements = Array.from(document.querySelectorAll(
                    mediaType === 'video' ? 'video[src*="media.getMediaUrlRedirect"], video[src]' : 'img[src*="media.getMediaUrlRedirect"], img[alt*="Üretilmiş resim" i], img[src*="googleusercontent"]'
                ));

                const validUrls = [];
                for (const media of mediaElements) {
                    const src = media.src || media.href;
                    if (src && !existingMedia.includes(src) && !src.startsWith('data:image/svg') && !src.includes('avatar') && !validUrls.includes(src)) {
                        validUrls.push(src);
                    }
                }

                if (validUrls.length >= expectedCount || (validUrls.length > 0 && Date.now() - startTime > 35000)) {
                    completed = true;
                    obs.disconnect();
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onGenerationCompleted(taskId, validUrls[0], JSON.stringify({
                            mediaType: mediaType,
                            urls: validUrls,
                            count: validUrls.length,
                            completedAt: Date.now()
                        }));
                    }
                    return;
                }

                if (Date.now() - startTime > timeoutMs) {
                    completed = true;
                    obs.disconnect();
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Timeout waiting for generation output");
                    }
                }
            });

            observer.observe(document.body, { childList: true, subtree: true, attributes: true });
        }
    };

    setInterval(() => {
        window.FlowAutomation.checkAuth();
    }, 5000);

    console.log("[FlowBridge] Google Flow Bridge v2.6 Ready.");
})();
