/**
 * Google Flow MCP Automation Bridge & Scraper
 * Injected into Google Flow WebView to provide full DOM automation,
 * mutation observation, asset extraction and MCP tool bindings.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge] Initializing Google Flow Automation Bridge...");

    const SELECTORS = {
        promptInputs: [
            'textarea[placeholder*="prompt" i]',
            'textarea[placeholder*="describe" i]',
            'div[contenteditable="true"]',
            'input[type="text"][placeholder*="prompt" i]',
            'textarea',
            '[data-testid="prompt-input"]'
        ],
        generateButtons: [
            'button:has(svg)',
            'button[aria-label*="generate" i]',
            'button[aria-label*="create" i]',
            'button:contains("Generate")',
            'button:contains("Create")',
            '[data-testid="generate-button"]'
        ],
        mediaItems: [
            'img[src*="googleusercontent"]',
            'video[src*="blob:"]',
            'video[src*="googleusercontent"]',
            '[data-testid="media-card"]'
        ],
        fileInputs: [
            'input[type="file"]',
            '[data-testid="file-upload-input"]'
        ],
        userProfile: [
            'button[aria-label*="Google Account" i]',
            'img[alt*="Google Account" i]',
            '[data-testid="user-profile"]'
        ]
    };

    function findElement(selectors) {
        for (const selector of selectors) {
            try {
                if (selector.includes(':contains(')) {
                    const text = selector.match(/:contains\("([^"]+)"\)/)[1];
                    const elements = Array.from(document.querySelectorAll('button, span, div'));
                    const found = elements.find(el => el.textContent && el.textContent.trim().toLowerCase().includes(text.toLowerCase()));
                    if (found) return found;
                } else {
                    const el = document.querySelector(selector);
                    if (el) return el;
                }
            } catch (e) {}
        }
        return null;
    }

    function triggerInputEvents(element, text) {
        element.focus();
        if (element.tagName.toLowerCase() === 'textarea' || element.tagName.toLowerCase() === 'input') {
            element.value = text;
            element.dispatchEvent(new Event('input', { bubbles: true }));
            element.dispatchEvent(new Event('change', { bubbles: true }));
        } else if (element.isContentEditable) {
            element.textContent = text;
            element.dispatchEvent(new Event('input', { bubbles: true }));
        }
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
            let credits = "Unknown";
            const creditEl = document.querySelector('[data-testid="credit-balance"], [aria-label*="credits" i]');
            if (creditEl) credits = creditEl.textContent.trim();

            const status = {
                url: window.location.href,
                isLoggedIn: this.checkAuth(),
                credits: credits,
                timestamp: Date.now()
            };
            return JSON.stringify(status);
        },

        generateImage: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] Starting image generation task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const inputEl = findElement(SELECTORS.promptInputs);
                if (!inputEl) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onError(taskId, "Prompt input element not found in DOM");
                    }
                    return false;
                }

                triggerInputEvents(inputEl, prompt);

                setTimeout(() => {
                    const btn = findElement(SELECTORS.generateButtons);
                    if (!btn) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onError(taskId, "Generate button not found");
                        }
                        return;
                    }
                    btn.click();
                    console.log("[FlowBridge] Generate clicked. Watching for output...");

                    // Monitor for generated image
                    this.watchForOutput(taskId, 'image', 120000);
                }, 500);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateImage error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        generateWithReference: function(taskId, prompt, base64Image, mimeType, filename) {
            console.log("[FlowBridge] Uploading reference image and generating:", taskId);
            try {
                const blob = base64ToBlob(base64Image, mimeType || 'image/png');
                const file = new File([blob], filename || 'reference.png', { type: mimeType || 'image/png' });

                const fileInput = findElement(SELECTORS.fileInputs);
                if (fileInput) {
                    const dataTransfer = new DataTransfer();
                    dataTransfer.items.add(file);
                    fileInput.files = dataTransfer.files;
                    fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                } else {
                    console.warn("[FlowBridge] No file input found, trying dropzone dispatch");
                }

                setTimeout(() => {
                    this.generateImage(taskId, prompt, null);
                }, 1000);

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
            console.log("[FlowBridge] Starting video generation task:", taskId, prompt);
            try {
                // Click video tab if present
                const videoTab = Array.from(document.querySelectorAll('button, div[role="tab"], a'))
                    .find(el => el.textContent && el.textContent.trim().toLowerCase() === 'video');
                if (videoTab) videoTab.click();

                setTimeout(() => {
                    this.generateImage(taskId, prompt, optionsJson);
                    this.watchForOutput(taskId, 'video', 300000); // 5 min timeout for video
                }, 800);

                return true;
            } catch (err) {
                console.error("[FlowBridge] generateVideo error:", err);
                if (window.AndroidBridge) {
                    window.AndroidBridge.onError(taskId, err.toString());
                }
                return false;
            }
        },

        watchForOutput: function(taskId, mediaType, timeoutMs) {
            const startTime = Date.now();
            let completed = false;

            const observer = new MutationObserver((mutations, obs) => {
                if (completed) return;

                const mediaElements = Array.from(document.querySelectorAll(
                    mediaType === 'video' ? 'video[src], a[download][href*=".mp4"]' : 'img[src*="googleusercontent"], a[download][href*=".png"], a[download][href*=".jpg"]'
                ));

                for (const media of mediaElements) {
                    const src = media.src || media.href;
                    if (src && !src.startsWith('data:image/svg') && !src.includes('avatar')) {
                        completed = true;
                        obs.disconnect();
                        console.log("[FlowBridge] Generation completed! Found media:", src);
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onGenerationCompleted(taskId, src, JSON.stringify({
                                mediaType: mediaType,
                                url: src,
                                completedAt: Date.now()
                            }));
                        }
                        return;
                    }
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

    // Continuous auth & health heartbeat
    setInterval(() => {
        window.FlowAutomation.checkAuth();
    }, 5000);

    console.log("[FlowBridge] Google Flow Automation Bridge Ready.");
})();
