/**
 * Google Flow Android MCP Bridge (v3.1 - React Fiber & Props Direct Invocation)
 * Directly invokes React onClick, onChange, onInput handlers from DOM nodes.
 */

(function() {
    if (window.FlowBridgeInitialized) return;
    window.FlowBridgeInitialized = true;

    console.log("[FlowBridge v3.1] Initializing React Fiber Engine...");

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

    function getReactProps(domNode) {
        if (!domNode) return null;
        const key = Object.keys(domNode).find(k => 
            k.startsWith('__reactProps$') || 
            k.startsWith('__reactEventHandlers$') || 
            k.startsWith('__reactFiber$')
        );
        return key ? domNode[key] : null;
    }

    function triggerReactClick(domButton) {
        if (!domButton) return false;

        domButton.removeAttribute('disabled');
        domButton.disabled = false;
        domButton.setAttribute('aria-disabled', 'false');

        // 1. Try direct React props onClick
        const props = getReactProps(domButton);
        if (props) {
            if (typeof props.onClick === 'function') {
                try {
                    props.onClick({
                        preventDefault: () => {},
                        stopPropagation: () => {},
                        target: domButton,
                        currentTarget: domButton
                    });
                    console.log("[FlowBridge] Successfully triggered React props.onClick() directly!");
                } catch (e) {
                    console.warn("[FlowBridge] React onClick error:", e);
                }
            }
            if (props.children && typeof props.children.onClick === 'function') {
                try { props.children.onClick({}); } catch (e) {}
            }
        }

        // 2. Dispatch Pointer & Mouse events
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(evt => {
            domButton.dispatchEvent(new MouseEvent(evt, {
                bubbles: true,
                cancelable: true,
                view: window,
                buttons: 1
            }));
        });

        try { domButton.click(); } catch(e) {}
        return true;
    }

    function writePromptToContentEditable(div, text) {
        div.focus();

        // 1. Construct DOM structure with <p> inside contenteditable
        div.innerHTML = `<p>${text}</p>`;

        // 2. Trigger React props onInput / onChange directly
        const props = getReactProps(div);
        if (props) {
            if (typeof props.onInput === 'function') {
                try {
                    props.onInput({
                        target: div,
                        currentTarget: div,
                        preventDefault: () => {},
                        stopPropagation: () => {}
                    });
                    console.log("[FlowBridge] Triggered React props.onInput()");
                } catch(e) {}
            }
            if (typeof props.onChange === 'function') {
                try {
                    props.onChange({
                        target: div,
                        currentTarget: div,
                        preventDefault: () => {},
                        stopPropagation: () => {}
                    });
                } catch(e) {}
            }
        }

        // 3. Dispatch native input events
        try {
            const inputEvt = new InputEvent('input', {
                bubbles: true,
                cancelable: true,
                inputType: 'insertText',
                data: text
            });
            div.dispatchEvent(inputEvt);
        } catch(e) {
            div.dispatchEvent(new Event('input', { bubbles: true }));
        }

        div.dispatchEvent(new Event('change', { bubbles: true }));

        // 4. Selection set to end of text
        try {
            const selection = window.getSelection();
            const range = document.createRange();
            range.selectNodeContents(div);
            range.collapse(false);
            selection.removeAllRanges();
            selection.addRange(range);
        } catch(e) {}

        return true;
    }

    function findAndClickSubmit() {
        const buttons = Array.from(document.querySelectorAll('button'));
        
        // Find button index with arrow_forward or Oluştur
        const submitBtn = buttons.find(b => {
            const txt = (b.textContent || '').trim();
            const cls = (b.className || '');
            return txt.includes('arrow_forward') || txt.includes('Oluştur') || txt.includes('Generate') || cls.includes('kmC');
        });

        if (submitBtn) {
            console.log("[FlowBridge] Found submit button:", submitBtn.textContent);
            triggerReactClick(submitBtn);
            if (window.AndroidBridge) window.AndroidBridge.log("✓ Triggered submit button: " + submitBtn.textContent);
            return true;
        }

        // Fallback: Dispatch Enter on contenteditable
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

    function checkAgentConfirmation() {
        const buttons = Array.from(document.querySelectorAll('button'));
        const confirmBtn = buttons.find(b => {
            const t = (b.textContent || '').trim().toLowerCase();
            return t.includes('accepter') || t.includes('approve') || t.includes('approva') || t.includes('onayla') || t.includes('kabul et');
        });
        if (confirmBtn) {
            console.log("[FlowBridge] Auto-confirming agent dialog:", confirmBtn.textContent);
            triggerReactClick(confirmBtn);
            return true;
        }
        return false;
    }

    function pollForOutput(taskId, mediaType, timeoutMs) {
        const startTime = Date.now();
        const checkInterval = setInterval(() => {
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
                console.log("[FlowBridge] New Generated Media UUID:", foundUuids[0]);
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
                captureBaselineUuids();

                const promptDiv = Array.from(document.querySelectorAll('div[contenteditable="true"]')).find(d => 
                    d.isContentEditable || (d.textContent && (d.textContent.includes('Ne oluşturmak') || d.textContent.includes('want to create')))
                ) || document.querySelector('div[contenteditable="true"], textarea');

                if (!promptDiv) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Prompt box not found");
                    return false;
                }

                // Construct imperative prompt for Flow agent
                const imperativePrompt = `Genera subito un'immagine, senza farmi domande e senza chiedere chiarimenti. Attieniti FEDELMENTE a questa descrizione: includi TUTTI gli elementi, soggetti e dettagli indicati, non aggiungere nulla che non sia richiesto e non omettere nulla. Descrizione: ${prompt}`;

                writePromptToContentEditable(promptDiv, imperativePrompt);

                // Multi-stage trigger sequence (0ms, 300ms, 700ms)
                setTimeout(() => { findAndClickSubmit(); }, 200);
                setTimeout(() => { findAndClickSubmit(); }, 500);
                setTimeout(() => {
                    findAndClickSubmit();
                    pollForOutput(taskId, 'image', 240000);
                }, 900);

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

                captureBaselineUuids();

                const promptDiv = document.querySelector('div[contenteditable="true"], textarea');
                if (!promptDiv) {
                    if (window.AndroidBridge) window.AndroidBridge.onError(taskId, "Prompt box not found");
                    return false;
                }

                const imperativePrompt = `Genera subito un video di ${duration} con il modello ${model}, senza farmi domande e senza chiedere chiarimenti. Attieniti FEDELMENTE a questa descrizione: includi TUTTI gli elementi, soggetti, azioni e dettagli indicati, non aggiungere nulla che non sia richiesto e non omettere nulla. Descrizione: ${prompt}`;

                writePromptToContentEditable(promptDiv, imperativePrompt);

                setTimeout(() => { findAndClickSubmit(); }, 200);
                setTimeout(() => { findAndClickSubmit(); }, 600);
                setTimeout(() => {
                    findAndClickSubmit();
                    pollForOutput(taskId, 'video', 480000);
                }, 1000);

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

    console.log("[FlowBridge v3.1] Ready.");
})();
