/**
 * Google Flow & FX Suite Automation Bridge & Scraper (v2.3)
 * Comprehensive React / Next.js Input State Sync & Generate Activation
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge] Initializing Google Flow & FX Automation Bridge v2.3...");

    const SELECTORS = {
        promptInputs: [
            'textarea[placeholder*="prompt" i]',
            'textarea[placeholder*="describe" i]',
            'textarea[placeholder*="image" i]',
            'textarea[placeholder*="video" i]',
            'textarea[placeholder*="expand" i]',
            'div[contenteditable="true"]',
            'div[role="textbox"]',
            'textarea',
            'input[type="text"][placeholder*="prompt" i]',
            '[data-testid="prompt-input"]',
            '.prompt-input',
            '[aria-label*="prompt" i]'
        ],
        generateButtons: [
            'button[aria-label*="generate" i]',
            'button[aria-label*="create" i]',
            'button[aria-label*="submit" i]',
            'button[aria-label*="send" i]',
            'button:contains("Generate")',
            'button:contains("Create")',
            'button:contains("Try in Google Flow")',
            'button:has(svg)',
            'button:has(i)',
            '[data-testid="generate-button"]'
        ],
        modelDropdown: [
            '[data-testid="model-selector"]',
            'button[aria-label*="model" i]',
            'button:contains("Nano Banana")',
            'button:contains("Veo")'
        ],
        aspectRatioButtons: [
            '[data-testid="aspect-ratio-selector"]',
            'button[aria-label*="aspect" i]',
            'button[aria-label*="ratio" i]'
        ],
        countButtons: [
            '[data-testid="batch-count"]',
            'button[aria-label*="count" i]',
            'button[aria-label*="outputs" i]'
        ],
        fileInputs: [
            'input[type="file"]',
            '[data-testid="file-upload-input"]'
        ],
        userProfile: [
            'button[aria-label*="Google Account" i]',
            'img[alt*="Google Account" i]',
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

    /**
     * Injects text into React / Next.js controlled input components
     * and forces React state synchronization.
     */
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

            // Dispatch React-compatible events
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

        // Trigger keyboard events to ensure buttons activate
        element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', charCode: 13, keyCode: 13, bubbles: true }));
        element.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', charCode: 13, keyCode: 13, bubbles: true }));
    }

    function clickMatchingButton(textPatterns) {
        const buttons = Array.from(document.querySelectorAll('button, div[role="button"], span, div[role="radio"], a'));
        for (const pattern of textPatterns) {
            const found = buttons.find(b => b.textContent && b.textContent.trim().toLowerCase().includes(pattern.toLowerCase()));
            if (found) {
                found.click();
                return true;
            }
        }
        return false;
    }

    function base64ToBlob(base64Data, contentType) {
        contentType = contentType || '';
        const byteCharacters = atob(base64Data);
        const byteArrays = [];
        const sliceSize = 512;

        for (let offset = 0; offset < byteCharacters.length; offset += sliceSize) {
            const slice = byteCharacters.slice(offset, offset + sliceSize);
            const byteNumbers = new Array(slice.length);
            for (let i = 0; i < slice.length; i++) {
                byteNumbers[i] = slice.charCodeAt(i);
            }
            const byteArray = new Uint8Array(byteNumbers);
            byteArrays.push(byteArray);
        }
        return new Blob(byteArrays, { type: contentType });
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

        getAccountInfo: function() {
            let credits = "Active";
            const creditEl = document.querySelector('[data-testid="credit-balance"], [aria-label*="credits" i]');
            if (creditEl) credits = creditEl.textContent.trim();

            const status = {
                url: window.location.href,
                isLoggedIn: this.checkAuth(),
                credits: credits,
                supportedModels: ["nano-banana-2", "nano-banana", "veo-3.1"],
                supportedAspectRatios: ["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"],
                maxOutputsCount: 4,
                timestamp: Date.now()
            };
            return JSON.stringify(status);
        },

        applyModelSettings: function(model, aspectRatio, count) {
            if (model) {
                if (model.includes("veo")) {
                    clickMatchingButton(["Veo 3.1", "Veo", "Video"]);
                } else {
                    clickMatchingButton(["Nano Banana 2", "Nano Banana", "Imagen"]);
                }
            }

            if (aspectRatio) {
                setTimeout(() => {
                    clickMatchingButton([aspectRatio, `Ratio ${aspectRatio}`, `Aspect ${aspectRatio}`]);
                }, 100);
            }

            if (count && count > 1) {
                setTimeout(() => {
                    clickMatchingButton([`${count}x`, `${count} images`, `${count} outputs`, `${count}`]);
                }, 200);
            }
        },

        generateImage: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateImage task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "nano-banana-2";
                const aspectRatio = options.aspectRatio || "1:1";
                const count = options.count || 1;

                this.applyModelSettings(model, aspectRatio, count);

                const inputEl = findElement(SELECTORS.promptInputs);
                if (!inputEl) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Prompt input not found on page: " + window.location.href);
                    }
                    return false;
                }

                // Force React input state update
                triggerReactInput(inputEl, prompt);

                // Wait 400ms for React state & button enable
                setTimeout(() => {
                    // Try finding enabled generate button
                    let btn = Array.from(document.querySelectorAll('button')).find(b => {
                        const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                        const text = (b.textContent || '').toLowerCase();
                        return (aria.includes('generate') || aria.includes('create') || text.includes('generate') || text.includes('create')) && !b.disabled;
                    });

                    if (!btn) {
                        btn = findElement(SELECTORS.generateButtons);
                    }

                    if (btn) {
                        btn.removeAttribute('disabled');
                        btn.disabled = false;
                        btn.click();
                        console.log("[FlowBridge] Generate button clicked successfully!");
                        this.watchForOutput(taskId, 'image', count, 180000);
                    } else {
                        // Fallback: send Enter keypress event to input
                        inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true }));
                        console.log("[FlowBridge] Dispatched Ctrl+Enter fallback.");
                        this.watchForOutput(taskId, 'image', count, 180000);
                    }
                }, 400);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateImage error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        generateWithReference: function(taskId, prompt, base64Image, mimeType, filename, optionsJson) {
            console.log("[FlowBridge] generateWithReference task:", taskId);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "nano-banana-2";
                const aspectRatio = options.aspectRatio || "1:1";
                const count = options.count || 1;

                this.applyModelSettings(model, aspectRatio, count);

                const blob = base64ToBlob(base64Image, mimeType || 'image/png');
                const file = new File([blob], filename || 'reference.png', { type: mimeType || 'image/png' });

                const fileInput = findElement(SELECTORS.fileInputs);
                if (fileInput) {
                    const dataTransfer = new DataTransfer();
                    dataTransfer.items.add(file);
                    fileInput.files = dataTransfer.files;
                    fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                }

                setTimeout(() => {
                    const inputEl = findElement(SELECTORS.promptInputs);
                    if (inputEl) triggerReactInput(inputEl, prompt);

                    setTimeout(() => {
                        const btn = findElement(SELECTORS.generateButtons);
                        if (btn) {
                            btn.removeAttribute('disabled');
                            btn.disabled = false;
                            btn.click();
                        }
                        this.watchForOutput(taskId, 'image', count, 200000);
                    }, 500);
                }, 800);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateWithReference error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        generateVideo: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateVideo (Veo 3.1) task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "veo-3.1";
                const aspectRatio = options.aspectRatio || "16:9";

                this.applyModelSettings(model, aspectRatio, 1);

                setTimeout(() => {
                    const inputEl = findElement(SELECTORS.promptInputs);
                    if (inputEl) triggerReactInput(inputEl, prompt);

                    setTimeout(() => {
                        const btn = findElement(SELECTORS.generateButtons);
                        if (btn) {
                            btn.removeAttribute('disabled');
                            btn.disabled = false;
                            btn.click();
                        }
                        this.watchForOutput(taskId, 'video', 1, 360000);
                    }, 500);
                }, 600);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateVideo error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        listProjects: function() {
            const projectEls = Array.from(document.querySelectorAll('a[href*="/project/"], [data-testid="project-item"], button[aria-label*="project" i]'));
            const projects = projectEls.map(p => ({
                name: p.textContent.trim(),
                url: p.href || "",
                id: (p.href || "").split('/project/')[1] || ""
            })).filter(p => p.name.length > 0);
            return JSON.stringify(projects);
        },

        createProject: function(name) {
            clickMatchingButton(["New Project", "Create Project", "+ Project"]);
            setTimeout(() => {
                const nameInput = document.querySelector('input[placeholder*="project name" i], input[type="text"]');
                if (nameInput) {
                    triggerReactInput(nameInput, name);
                    setTimeout(() => {
                        clickMatchingButton(["Create", "Save", "Confirm"]);
                    }, 300);
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
                    console.log("[FlowBridge] Found outputs:", validUrls);
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
        },

        discoverUi: function() {
            const buttons = Array.from(document.querySelectorAll('button')).map(b => ({
                text: b.textContent.trim(),
                aria: b.getAttribute('aria-label'),
                testId: b.getAttribute('data-testid')
            }));
            const inputs = Array.from(document.querySelectorAll('input, textarea')).map(i => ({
                placeholder: i.getAttribute('placeholder'),
                type: i.getAttribute('type'),
                testId: i.getAttribute('data-testid')
            }));
            return JSON.stringify({ buttons, inputs, url: window.location.href });
        }
    };

    setInterval(() => {
        window.FlowAutomation.checkAuth();
    }, 5000);

    console.log("[FlowBridge] Google Flow & FX Suite v2.3 Ready.");
})();
