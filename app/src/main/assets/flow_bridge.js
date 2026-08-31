/**
 * Google Flow & FX Suite Automation Bridge & Scraper (v2.4)
 * Comprehensive DOM Inspector, Multi-Language Landing Auto-Enter,
 * and Full Page Structure Dumper.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge] Initializing Google Flow Bridge v2.4...");

    const SELECTORS = {
        promptInputs: [
            'textarea[placeholder*="prompt" i]',
            'textarea[placeholder*="describe" i]',
            'textarea[placeholder*="image" i]',
            'textarea[placeholder*="video" i]',
            'textarea[placeholder*="tarif" i]',
            'textarea[placeholder*="açıkla" i]',
            'div[contenteditable="true"]',
            'div[role="textbox"]',
            'textarea',
            'input[type="text"]',
            '[data-testid="prompt-input"]',
            '.prompt-input',
            '[aria-label*="prompt" i]'
        ],
        generateButtons: [
            'button[aria-label*="generate" i]',
            'button[aria-label*="create" i]',
            'button[aria-label*="üret" i]',
            'button[aria-label*="oluştur" i]',
            'button:contains("Generate")',
            'button:contains("Create")',
            'button:contains("Üret")',
            'button:contains("Oluştur")',
            'button:contains("Try in Google Flow")',
            'button:contains("Google Flow\'da Deneyin")',
            'button:has(svg)',
            '[data-testid="generate-button"]'
        ],
        landingTryButtons: [
            'button:contains("Try in Google Flow")',
            'button:contains("Google Flow\'da Deneyin")',
            'a:contains("Try in Google Flow")',
            'a:contains("Google Flow\'da Deneyin")',
            'button:contains("Deneyin")',
            'button:contains("Try")'
        ],
        userProfile: [
            'button[aria-label*="Google Account" i]',
            'button[aria-label*="Google Hesabı" i]',
            'img[alt*="Google Account" i]',
            'img[alt*="Google Hesabı" i]',
            '[data-testid="user-profile"]',
            'button[aria-label*="Account" i]'
        ]
    };

    function findElement(selectors) {
        for (const selector of selectors) {
            try {
                if (selector.includes(':contains(')) {
                    const text = selector.match(/:contains\("([^"]+)"\)/)[1];
                    const elements = Array.from(document.querySelectorAll('button, span, div, a, p'));
                    const found = elements.find(el => el.textContent && el.textContent.trim().toLowerCase().includes(text.toLowerCase()));
                    if (found) return found;
                } else if (selector.includes(':has(')) {
                    const childTag = selector.match(/:has\(([^)]+)\)/)[1];
                    const elements = Array.from(document.querySelectorAll('button, div[role="button"]'));
                    const found = elements.find(el => el.querySelector(childTag));
                    if (found) return found;
                } else {
                    const el = document.querySelector(selector);
                    if (el) return el;
                }
            } catch (e) {}
        }
        return null;
    }

    function triggerReactInput(element, text) {
        element.focus();

        if (element.tagName.toLowerCase() === 'textarea' || element.tagName.toLowerCase() === 'input') {
            const proto = element.tagName.toLowerCase() === 'textarea' 
                ? window.HTMLTextAreaElement.prototype 
                : window.HTMLInputElement.prototype;

            const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
            if (nativeSetter) {
                nativeSetter.call(element, text);
            } else {
                element.value = text;
            }

            element.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            element.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
        } else if (element.isContentEditable || element.getAttribute('contenteditable') === 'true') {
            element.focus();
            try {
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, text);
            } catch (e) {
                element.textContent = text;
            }
            element.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
        }

        element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', charCode: 13, keyCode: 13, bubbles: true }));
        element.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', charCode: 13, keyCode: 13, bubbles: true }));
    }

    window.FlowAutomation = {
        checkAuth: function() {
            const hasProfile = !!findElement(SELECTORS.userProfile);
            const currentUrl = window.location.href;
            const isLoggedIn = hasProfile || (!currentUrl.includes('accounts.google.com') && !currentUrl.includes('ServiceLogin'));
            
            if (window.AndroidBridge && window.AndroidBridge.onAuthStatus) {
                window.AndroidBridge.onAuthStatus(isLoggedIn, currentUrl);
            }
            return isLoggedIn;
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
                    src: (m.src || m.href || '').slice(0, 120),
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

                // Log full DOM dump before executing so we can trace exact elements
                this.dumpFullDom();

                // If on landing page, try clicking "Try in Google Flow" first
                const landingBtn = findElement(SELECTORS.landingTryButtons);
                if (landingBtn) {
                    if (window.AndroidBridge) window.AndroidBridge.log("Found landing button, clicking to enter workspace: " + landingBtn.textContent);
                    landingBtn.click();
                }

                let inputEl = findElement(SELECTORS.promptInputs);
                if (!inputEl) {
                    // Re-check after 1s in case page transitioned
                    setTimeout(() => {
                        inputEl = findElement(SELECTORS.promptInputs);
                        if (inputEl) {
                            triggerReactInput(inputEl, prompt);
                            this.triggerGenerate(taskId, count);
                        } else {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onError(taskId, "Prompt input not found! URL: " + window.location.href);
                            }
                        }
                    }, 1000);
                    return true;
                }

                triggerReactInput(inputEl, prompt);
                this.triggerGenerate(taskId, count);
                return true;
            } catch (err) {
                console.error("[FlowBridge] generateImage error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        triggerGenerate: function(taskId, count) {
            setTimeout(() => {
                let btn = Array.from(document.querySelectorAll('button')).find(b => {
                    const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                    const text = (b.textContent || '').toLowerCase();
                    return (aria.includes('generate') || aria.includes('create') || aria.includes('üret') || aria.includes('oluştur') || text.includes('generate') || text.includes('create') || text.includes('üret') || text.includes('oluştur')) && !b.disabled;
                });

                if (!btn) {
                    btn = findElement(SELECTORS.generateButtons);
                }

                if (btn) {
                    btn.removeAttribute('disabled');
                    btn.disabled = false;
                    btn.click();
                    if (window.AndroidBridge) window.AndroidBridge.log("Generate button clicked: " + (btn.textContent || btn.getAttribute('aria-label')));
                    this.watchForOutput(taskId, 'image', count, 180000);
                } else {
                    if (window.AndroidBridge) window.AndroidBridge.log("Generate button not found, dishing Enter key.");
                    const inputEl = findElement(SELECTORS.promptInputs);
                    if (inputEl) {
                        inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true }));
                    }
                    this.watchForOutput(taskId, 'image', count, 180000);
                }
            }, 500);
        },

        watchForOutput: function(taskId, mediaType, expectedCount, timeoutMs) {
            const startTime = Date.now();
            let completed = false;
            expectedCount = expectedCount || 1;

            const observer = new MutationObserver((mutations, obs) => {
                if (completed) return;

                const mediaElements = Array.from(document.querySelectorAll(
                    mediaType === 'video' ? 'video[src], a[download][href*=".mp4"]' : 'img[src*="googleusercontent"], a[download][href*=".png"], a[download][href*=".jpg"]'
                ));

                const validUrls = [];
                for (const media of mediaElements) {
                    const src = media.src || media.href;
                    if (src && !src.startsWith('data:image/svg') && !src.includes('avatar') && !validUrls.includes(src)) {
                        validUrls.push(src);
                    }
                }

                if (validUrls.length >= expectedCount || (validUrls.length > 0 && Date.now() - startTime > 30000)) {
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

    console.log("[FlowBridge] Google Flow Bridge v2.4 Ready.");
})();
