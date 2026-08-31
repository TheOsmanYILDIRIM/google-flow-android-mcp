/**
 * Google Flow Android MCP Bridge (v3.0 - Desktop Playwright Engine Port)
 * Direct 1:1 port of GabrielGargiuloDev/google-flow-mcp
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge v3.0] Initializing Google Flow Desktop Port...");

    // Baseline UUID tracker to ensure we only capture newly generated images
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

    function forceClick(element) {
        if (!element) return false;
        element.removeAttribute('disabled');
        element.disabled = false;
        element.setAttribute('aria-disabled', 'false');

        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evtType => {
            element.dispatchEvent(new MouseEvent(evtType, {
                bubbles: true,
                cancelable: true,
                view: window,
                buttons: 1
            }));
        });
        try { element.click(); } catch(e) {}
        return true;
    }

    function ensureProject() {
        if (window.location.href.includes('/project/')) {
            return true;
        }

        // Try clicking New project button
        const newBtn = Array.from(document.querySelectorAll('button, a')).find(b => {
            const t = (b.textContent || '').trim().toLowerCase();
            return t.includes('new project') || t.includes('nuovo progetto') || t.includes('nouveau projet') || t.includes('yeni proje') || t.includes('proje oluştur');
        });

        if (newBtn) {
            forceClick(newBtn);
            return true;
        }

        // Try finding any existing project card
        const projectLink = document.querySelector('a[href*="/project/"]');
        if (projectLink) {
            projectLink.click();
            return true;
        }

        return false;
    }

    function switchToImageTab() {
        const imageTab = document.querySelector('button[role="tab"][id*="trigger-IMAGE"], button[role="tab"]:has-text("Image"), button[role="tab"]:has-text("Resim")');
        if (imageTab) {
            forceClick(imageTab);
        }
    }

    function checkAgentConfirmation() {
        const buttons = Array.from(document.querySelectorAll('button'));
        const confirmBtn = buttons.find(b => {
            const t = (b.textContent || '').trim().toLowerCase();
            return t.includes('accepter') || t.includes('approve') || t.includes('approva') || t.includes('onayla') || t.includes('kabul et');
        });
        if (confirmBtn) {
            console.log("[FlowBridge] Agent confirmation dialog detected, approving:", confirmBtn.textContent);
            forceClick(confirmBtn);
            return true;
        }
        return false;
    }

    function setPromptText(prompt, isVideo, model, duration) {
        // Find contenteditable div or textarea (Agent bar)
        let promptInput = document.querySelector('[contenteditable="true"]:not([aria-hidden="true"])') ||
                          document.querySelector('div[contenteditable="true"]') ||
                          document.querySelector('textarea:not([aria-hidden="true"])') ||
                          document.querySelector('textarea');

        if (!promptInput) {
            return false;
        }

        promptInput.focus();

        // GabrielGargiuloDev Imperative Prompt Formulation
        let imperativePrompt = "";
        if (isVideo) {
            imperativePrompt = `Genera subito un video di ${duration || '4s'} con il modello ${model || 'Veo 3.1'}, senza farmi domande e senza chiedere chiarimenti. Attieniti FEDELMENTE a questa descrizione: includi TUTTI gli elementi, soggetti, azioni e dettagli indicati, non aggiungere nulla che non sia richiesto e non omettere nulla. Descrizione: ${prompt}`;
        } else {
            imperativePrompt = `Genera subito un'immagine, senza farmi domande e senza chiedere chiarimenti. Attieniti FEDELMENTE a questa descrizione: includi TUTTI gli elementi, soggetti e dettagli indicati, non aggiungere nulla che non sia richiesto e non omettere nulla. Descrizione: ${prompt}`;
        }

        // Selection clear & execCommand
        if (promptInput.isContentEditable || promptInput.getAttribute('contenteditable') === 'true') {
            const selection = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(promptInput);
            selection.removeAllRanges();
            selection.addRange(range);

            document.execCommand('delete', false, null);

            try {
                promptInput.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: imperativePrompt
                }));
            } catch(e) {}

            document.execCommand('insertText', false, imperativePrompt);

            if (!promptInput.textContent.includes(imperativePrompt)) {
                promptInput.textContent = imperativePrompt;
            }

            promptInput.dispatchEvent(new Event('input', { bubbles: true }));
            promptInput.dispatchEvent(new Event('change', { bubbles: true }));
        } else {
            promptInput.value = imperativePrompt;
            promptInput.dispatchEvent(new Event('input', { bubbles: true }));
            promptInput.dispatchEvent(new Event('change', { bubbles: true }));
        }

        return true;
    }

    function submitGeneration() {
        const buttons = Array.from(document.querySelectorAll('button'));
        
        // Exact GabrielGargiuloDev selector: button containing material icon 'arrow_forward'
        let submitBtn = buttons.find(b => {
            const txt = (b.textContent || '').trim();
            return txt.includes('arrow_forward') || txt.includes('Oluştur') || txt.includes('Generate') || txt.includes('Create');
        });

        if (submitBtn) {
            console.log("[FlowBridge] Found submit button:", submitBtn.textContent);
            forceClick(submitBtn);
            if (window.AndroidBridge) window.AndroidBridge.log("✓ Clicked submit button: " + submitBtn.textContent);
            return true;
        }

        // Fallback: Dispatched Enter
        const promptInput = document.querySelector('[contenteditable="true"], textarea');
        if (promptInput) {
            promptInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true }));
        }
        return false;
    }

    function pollForOutput(taskId, mediaType, timeoutMs) {
        const startTime = Date.now();
        const checkInterval = setInterval(() => {
            // Check for agent confirmation every tick
            checkAgentConfirmation();

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
                console.log("[FlowBridge] Generated media UUID detected:", foundUuids[0]);
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
                supportedModels: ["Nano Banana 2", "Nano Banana", "Veo 3.1 - Fast", "Veo 3.1 - Quality", "Omni Flash"],
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
                return JSON.stringify({ error: err.toString() });
            }
        },

        generateImage: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateImage task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "Nano Banana 2";

                ensureProject();
                switchToImageTab();
                captureBaselineUuids();

                const filled = setPromptText(prompt, false, model);
                if (!filled) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Could not find prompt input field");
                    return false;
                }

                // Wait 400ms then click submit
                setTimeout(() => {
                    submitGeneration();
                    // Additional trigger after 800ms to ensure click registers
                    setTimeout(() => { submitGeneration(); }, 800);
                    pollForOutput(taskId, 'image', 240000);
                }, 400);

                return true;
            } catch (err) {
                if (window.AndroidBridge) window.AndroidBridge.onError(taskId, err.toString());
                return false;
            }
        },

        generateVideo: function(taskId, prompt, optionsJson) {
            console.log("[FlowBridge] generateVideo task:", taskId, prompt);
            try {
                const options = optionsJson ? JSON.parse(optionsJson) : {};
                const model = options.model || "Veo 3.1 - Fast";
                const duration = options.duration || "4s";

                ensureProject();
                captureBaselineUuids();

                const filled = setPromptText(prompt, true, model, duration);
                if (!filled) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Could not find prompt input field");
                    return false;
                }

                setTimeout(() => {
                    submitGeneration();
                    setTimeout(() => { submitGeneration(); }, 800);
                    pollForOutput(taskId, 'video', 480000);
                }, 400);

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

    console.log("[FlowBridge v3.0] Ready.");
})();
