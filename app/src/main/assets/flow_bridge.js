/**
 * Google Flow & FX Suite Automation Bridge & Scraper (v2.5)
 * Built with EXACT verified DOM architecture from live Google Flow Studio.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge] Initializing Google Flow Bridge v2.5 (Verified Selectors)...");

    const SELECTORS = {
        promptDivs: [
            'div[contenteditable="true"]',
            'div[role="textbox"]',
            'textarea[placeholder*="Ne oluşturmak" i]',
            'textarea[placeholder*="want to create" i]',
            'textarea'
        ],
        generateButtons: [
            'button:contains("Oluştur")',
            'button:contains("arrow_forward")',
            'button:contains("Generate")',
            'button:contains("Create")',
            'button[aria-label*="Oluştur" i]',
            'button[aria-label*="Generate" i]',
            'button[aria-label*="Create" i]'
        ],
        modelSettingsButton: [
            'button:contains("Nano Banana")',
            'button:contains("Veo")',
            'button:contains("🍌")',
            'button:contains("crop_square")'
        ],
        mediaOutputs: [
            'img[src*="media.getMediaUrlRedirect"]',
            'video[src*="media.getMediaUrlRedirect"]',
            'img[alt*="Üretilmiş resim" i]',
            'img[alt*="Generated image" i]',
            'a[download]'
        ]
    };

    function findElement(selectors) {
        for (const selector of selectors) {
            try {
                if (selector.includes(':contains(')) {
                    const text = selector.match(/:contains\("([^"]+)"\)/)[1];
                    const elements = Array.from(document.querySelectorAll('button, span, div, a, p'));
                    const found = elements.find(el => el.textContent && el.textContent.includes(text));
                    if (found) return found;
                } else {
                    const el = document.querySelector(selector);
                    if (el) return el;
                }
            } catch (e) {}
        }
        return null;
    }

    function setPromptText(text) {
        // 1. Look for contenteditable DIV
        let promptEl = Array.from(document.querySelectorAll('div[contenteditable="true"]')).find(d => 
            d.isContentEditable || (d.textContent && (d.textContent.includes('Ne oluşturmak') || d.textContent.includes('want to create')))
        ) || document.querySelector('div[contenteditable="true"]');

        if (!promptEl) {
            promptEl = document.querySelector('textarea, input[type="text"]');
        }

        if (!promptEl) return false;

        promptEl.focus();

        if (promptEl.isContentEditable || promptEl.getAttribute('contenteditable') === 'true') {
            // Select all contents and replace with text
            const selection = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(promptEl);
            selection.removeAllRanges();
            selection.addRange(range);

            document.execCommand('delete', false, null);
            document.execCommand('insertText', false, text);

            if (!promptEl.textContent.includes(text)) {
                promptEl.textContent = text;
            }

            promptEl.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            promptEl.dispatchEvent(new InputEvent('input', {
                bubbles: true,
                cancelable: true,
                inputType: 'insertText',
                data: text
            }));
            promptEl.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        } else {
            const proto = promptEl.tagName.toLowerCase() === 'textarea'
                ? window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement.prototype;
            const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
            if (nativeSetter) nativeSetter.call(promptEl, text);
            else promptEl.value = text;

            promptEl.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            promptEl.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
        }

        // Dispatch keyboard events
        promptEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
        promptEl.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true }));

        return true;
    }

    function clickGenerateButton() {
        const buttons = Array.from(document.querySelectorAll('button'));
        
        // Exact match for "arrow_forwardOluştur" or "Oluştur" / "Generate"
        let genBtn = buttons.find(b => {
            const txt = (b.textContent || '').trim();
            return (txt.includes('Oluştur') && txt.includes('arrow_forward')) || 
                   txt === 'arrow_forwardOluştur' || 
                   txt.includes('Generate') || 
                   txt.includes('Create');
        });

        if (!genBtn) {
            genBtn = buttons.find(b => {
                const txt = (b.textContent || '').trim();
                return txt.includes('Oluştur') || txt.includes('arrow_forward');
            });
        }

        if (genBtn) {
            genBtn.removeAttribute('disabled');
            genBtn.disabled = false;
            genBtn.click();
            if (window.AndroidBridge) {
                window.AndroidBridge.log("✓ Generate button clicked: " + genBtn.textContent.trim());
            }
            return true;
        }

        // Fallback: Dispatched Ctrl+Enter
        const promptEl = document.querySelector('div[contenteditable="true"], textarea');
        if (promptEl) {
            promptEl.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Enter',
                code: 'Enter',
                keyCode: 13,
                which: 13,
                ctrlKey: true,
                bubbles: true
            }));
            if (window.AndroidBridge) {
                window.AndroidBridge.log("Dispatched Ctrl+Enter fallback.");
            }
            return true;
        }

        return false;
    }

    function applyModelSettings(model, aspectRatio, count) {
        const settingsBtn = Array.from(document.querySelectorAll('button')).find(b => {
            const txt = (b.textContent || '');
            return txt.includes('Nano Banana') || txt.includes('Veo') || txt.includes('🍌') || txt.includes('crop_square');
        });

        if (settingsBtn) {
            settingsBtn.click();
            setTimeout(() => {
                const options = Array.from(document.querySelectorAll('button, div[role="menuitem"], div[role="radio"], span'));
                
                // Model selection
                if (model) {
                    const mOption = options.find(o => o.textContent && o.textContent.toLowerCase().includes(model.toLowerCase()));
                    if (mOption) mOption.click();
                }

                // Ratio selection
                if (aspectRatio) {
                    const rOption = options.find(o => o.textContent && o.textContent.includes(aspectRatio));
                    if (rOption) rOption.click();
                }

                // Count selection
                if (count && count > 1) {
                    const cOption = options.find(o => o.textContent && o.textContent.includes(`x${count}`));
                    if (cOption) cOption.click();
                }

                // Close settings popup by clicking outside or pressing Escape
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

                const success = setPromptText(prompt);
                if (!success) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Could not find prompt contenteditable box on page: " + window.location.href);
                    }
                    return false;
                }

                // Give React 350ms to activate button state
                setTimeout(() => {
                    clickGenerateButton();
                    this.watchForOutput(taskId, 'image', count, 200000);
                }, 350);

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

                const success = setPromptText(prompt);
                if (!success) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Could not find prompt input");
                    }
                    return false;
                }

                setTimeout(() => {
                    clickGenerateButton();
                    this.watchForOutput(taskId, 'video', 1, 400000);
                }, 350);

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

            // Collect existing media to only detect new output
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

                // If no new ones found yet, check if any media redirected
                if (validUrls.length === 0 && mediaElements.length > 0 && Date.now() - startTime > 15000) {
                    for (const media of mediaElements) {
                        const src = media.src || media.href;
                        if (src && (src.includes('media.getMediaUrlRedirect') || src.includes('googleusercontent')) && !validUrls.includes(src)) {
                            validUrls.push(src);
                        }
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

    console.log("[FlowBridge] Google Flow Bridge v2.5 Ready.");
})();
