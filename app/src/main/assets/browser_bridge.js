/**
 * Universal Android Browser Bridge (v4.0)
 * Deep Network Traffic Sniffer, Console Capturer, Interactive DOM Analyzer & Synthetic Event Dispatcher.
 */

(function() {
    if (window.__AGY_BROWSER_INITIALIZED__) return;
    window.__AGY_BROWSER_INITIALIZED__ = true;

    console.log("[AgyBrowserBridge v4.0] Initializing Universal Web Sniffer & Bridge...");

    const bridge = window.AndroidBridge;

    function safeStringify(obj, maxLen = 32768) {
        try {
            const str = JSON.stringify(obj);
            if (str && str.length > maxLen) {
                return str.substring(0, maxLen) + "... [TRUNCATED]";
            }
            return str;
        } catch (e) {
            return String(obj);
        }
    }

    function notifyBridgeTraffic(eventData) {
        if (bridge && typeof bridge.onNetworkTraffic === 'function') {
            try {
                bridge.onNetworkTraffic(JSON.stringify(eventData));
            } catch (e) {
                console.error("[AgyBrowserBridge] onNetworkTraffic bridge error:", e);
            }
        }
    }

    function notifyBridgeConsole(level, message, stack) {
        if (bridge && typeof bridge.onConsoleLog === 'function') {
            try {
                bridge.onConsoleLog(level, message, stack || "");
            } catch (e) {}
        }
    }

    // =========================================================================
    // 1. CONSOLE & ERROR INTERCEPTION
    // =========================================================================
    const originalConsole = {
        log: console.log.bind(console),
        info: console.info.bind(console),
        warn: console.warn.bind(console),
        error: console.error.bind(console),
        debug: console.debug.bind(console)
    };

    ['log', 'info', 'warn', 'error', 'debug'].forEach(level => {
        console[level] = function(...args) {
            originalConsole[level](...args);
            try {
                const msg = args.map(a => (typeof a === 'object' ? safeStringify(a, 2048) : String(a))).join(' ');
                const stack = new Error().stack || "";
                notifyBridgeConsole(level, msg, stack);
            } catch (e) {}
        };
    });

    window.addEventListener('error', function(event) {
        notifyBridgeConsole('error', `Uncaught Error: ${event.message} at ${event.filename}:${event.lineno}`, event.error?.stack || "");
    });

    window.addEventListener('unhandledrejection', function(event) {
        notifyBridgeConsole('error', `Unhandled Promise Rejection: ${event.reason}`, event.reason?.stack || "");
    });

    // =========================================================================
    // 2. FETCH INTERCEPTION (Deep Request/Response Traffic Sniffer)
    // =========================================================================
    const originalFetch = window.fetch;
    let reqSeq = 0;

    window.fetch = async function(...args) {
        const id = 'fetch_' + (++reqSeq) + '_' + Date.now();
        const startTime = Date.now();
        let url = "";
        let method = "GET";
        let reqHeaders = {};
        let reqBody = null;

        try {
            if (typeof args[0] === 'string') {
                url = args[0];
            } else if (args[0] instanceof Request) {
                url = args[0].url;
                method = args[0].method || "GET";
            }

            if (args[1]) {
                if (args[1].method) method = args[1].method.toUpperCase();
                if (args[1].headers) {
                    if (args[1].headers instanceof Headers) {
                        args[1].headers.forEach((v, k) => { reqHeaders[k] = v; });
                    } else if (typeof args[1].headers === 'object') {
                        reqHeaders = { ...args[1].headers };
                    }
                }
                if (args[1].body) {
                    if (typeof args[1].body === 'string') {
                        reqBody = args[1].body;
                    } else if (args[1].body instanceof FormData) {
                        reqBody = "[FormData]";
                    } else {
                        reqBody = safeStringify(args[1].body, 4096);
                    }
                }
            }
        } catch (e) {
            console.warn("[AgyBrowserBridge] Fetch inspect req error:", e);
        }

        try {
            const response = await originalFetch.apply(this, args);
            const durationMs = Date.now() - startTime;
            const clonedResponse = response.clone();

            let respHeaders = {};
            try {
                clonedResponse.headers.forEach((v, k) => { respHeaders[k] = v; });
            } catch (e) {}

            let respBody = "";
            try {
                const contentType = clonedResponse.headers.get("content-type") || "";
                if (contentType.includes("json") || contentType.includes("text") || contentType.includes("xml") || contentType.includes("javascript")) {
                    const text = await clonedResponse.text();
                    respBody = text.length > 65536 ? text.substring(0, 65536) + "... [TRUNCATED]" : text;
                } else {
                    respBody = `[Binary / Non-text: ${contentType}]`;
                }
            } catch (e) {
                respBody = `[Error reading response body: ${e.message}]`;
            }

            notifyBridgeTraffic({
                id: id,
                type: 'fetch',
                timestamp: new Date().toISOString(),
                method: method,
                url: url,
                requestHeaders: reqHeaders,
                requestBody: reqBody,
                status: response.status,
                statusText: response.statusText,
                responseHeaders: respHeaders,
                responseBody: respBody,
                durationMs: durationMs
            });

            return response;
        } catch (err) {
            const durationMs = Date.now() - startTime;
            notifyBridgeTraffic({
                id: id,
                type: 'fetch',
                timestamp: new Date().toISOString(),
                method: method,
                url: url,
                requestHeaders: reqHeaders,
                requestBody: reqBody,
                status: 0,
                statusText: "Network Error",
                responseHeaders: {},
                responseBody: err.message,
                durationMs: durationMs,
                error: err.message
            });
            throw err;
        }
    };

    // =========================================================================
    // 3. XMLHTTPREQUEST INTERCEPTION
    // =========================================================================
    const OriginalXHR = window.XMLHttpRequest;
    function CustomXHR() {
        const xhr = new OriginalXHR();
        const id = 'xhr_' + (++reqSeq) + '_' + Date.now();
        let startTime = Date.now();
        let method = "GET";
        let url = "";
        let reqHeaders = {};
        let reqBody = null;

        const origOpen = xhr.open;
        xhr.open = function(m, u, ...rest) {
            method = (m || "GET").toUpperCase();
            url = u;
            return origOpen.call(xhr, m, u, ...rest);
        };

        const origSetHeader = xhr.setRequestHeader;
        xhr.setRequestHeader = function(header, value) {
            reqHeaders[header] = value;
            return origSetHeader.call(xhr, header, value);
        };

        const origSend = xhr.send;
        xhr.send = function(body) {
            startTime = Date.now();
            if (typeof body === 'string') {
                reqBody = body;
            } else if (body) {
                reqBody = safeStringify(body, 4096);
            }

            xhr.addEventListener('loadend', function() {
                const durationMs = Date.now() - startTime;
                let respHeaders = {};
                try {
                    const rawHeaders = xhr.getAllResponseHeaders();
                    rawHeaders.split("\r\n").forEach(line => {
                        const parts = line.split(": ");
                        if (parts[0]) respHeaders[parts[0]] = parts.slice(1).join(": ");
                    });
                } catch (e) {}

                let respBody = "";
                try {
                    if (xhr.responseType === "" || xhr.responseType === "text" || xhr.responseType === "json") {
                        const text = typeof xhr.response === 'object' ? JSON.stringify(xhr.response) : xhr.responseText;
                        respBody = text && text.length > 65536 ? text.substring(0, 65536) + "... [TRUNCATED]" : (text || "");
                    } else {
                        respBody = `[Binary: ${xhr.responseType}]`;
                    }
                } catch (e) {
                    respBody = `[Error reading response: ${e.message}]`;
                }

                notifyBridgeTraffic({
                    id: id,
                    type: 'xhr',
                    timestamp: new Date().toISOString(),
                    method: method,
                    url: url,
                    requestHeaders: reqHeaders,
                    requestBody: reqBody,
                    status: xhr.status,
                    statusText: xhr.statusText,
                    responseHeaders: respHeaders,
                    responseBody: respBody,
                    durationMs: durationMs
                });
            });

            return origSend.call(xhr, body);
        };

        return xhr;
    }

    // Preserve static props on XMLHttpRequest
    for (const key in OriginalXHR) {
        CustomXHR[key] = OriginalXHR[key];
    }
    CustomXHR.prototype = OriginalXHR.prototype;
    window.XMLHttpRequest = CustomXHR;

    // =========================================================================
    // 4. INTERACTIVE DOM API & SYNTHETIC CONTROL
    // =========================================================================
    window.__AGY_BROWSER__ = {
        getDom: function(format = 'html', selector = null) {
            const root = selector ? document.querySelector(selector) : document.documentElement;
            if (!root) return { success: false, error: `Selector '${selector}' not found` };

            if (format === 'html') {
                return {
                    success: true,
                    url: window.location.href,
                    title: document.title,
                    html: root.outerHTML
                };
            }

            if (format === 'text') {
                return {
                    success: true,
                    url: window.location.href,
                    title: document.title,
                    text: root.innerText || root.textContent
                };
            }

            if (format === 'interactive' || format === 'tree') {
                const elements = [];
                const interactiveQuery = 'button, a, input, textarea, select, [role="button"], [role="link"], [role="checkbox"], [contenteditable="true"]';
                const found = Array.from(root.querySelectorAll(interactiveQuery));

                found.forEach((el, index) => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    const isVisible = style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0' && rect.width > 0 && rect.height > 0;

                    if (!isVisible) return;

                    let tag = el.tagName.toLowerCase();
                    let type = el.getAttribute('type') || (tag === 'button' ? 'button' : tag === 'a' ? 'link' : tag);
                    let text = (el.innerText || el.textContent || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.value || "").trim();
                    if (text.length > 80) text = text.substring(0, 77) + "...";

                    let selectorPath = "";
                    if (el.id) {
                        selectorPath = `#${el.id}`;
                    } else if (el.name) {
                        selectorPath = `${tag}[name="${el.name}"]`;
                    } else if (el.getAttribute('data-testid')) {
                        selectorPath = `[data-testid="${el.getAttribute('data-testid')}"]`;
                    } else if (el.className && typeof el.className === 'string' && el.className.trim()) {
                        const firstClass = el.className.trim().split(/\s+/)[0];
                        selectorPath = `${tag}.${firstClass}`;
                    } else {
                        selectorPath = tag;
                    }

                    elements.push({
                        index: index,
                        tag: tag,
                        type: type,
                        id: el.id || null,
                        text: text,
                        value: el.value || null,
                        placeholder: el.placeholder || null,
                        disabled: el.disabled || false,
                        checked: el.checked || false,
                        selector: selectorPath,
                        rect: {
                            x: Math.round(rect.left),
                            y: Math.round(rect.top),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height)
                        }
                    });
                });

                return {
                    success: true,
                    url: window.location.href,
                    title: document.title,
                    totalElements: elements.length,
                    elements: elements
                };
            }

            return { success: false, error: `Unknown format: ${format}` };
        },

        click: function(selector, textMatch = null) {
            let target = null;
            if (selector) {
                target = document.querySelector(selector);
            }
            if (!target && textMatch) {
                const candidates = Array.from(document.querySelectorAll('button, a, [role="button"], span, div'));
                target = candidates.find(c => (c.innerText || "").trim().toLowerCase().includes(textMatch.toLowerCase()));
            }

            if (!target) {
                return { success: false, error: `Element not found (selector: ${selector}, textMatch: ${textMatch})` };
            }

            target.scrollIntoView({ behavior: 'auto', block: 'center' });

            const rect = target.getBoundingClientRect();
            const clientX = rect.left + rect.width / 2;
            const clientY = rect.top + rect.height / 2;

            const opts = {
                bubbles: true,
                cancelable: true,
                view: window,
                clientX: clientX,
                clientY: clientY
            };

            target.dispatchEvent(new PointerEvent('pointerdown', opts));
            target.dispatchEvent(new MouseEvent('mousedown', opts));
            target.dispatchEvent(new PointerEvent('pointerup', opts));
            target.dispatchEvent(new MouseEvent('mouseup', opts));
            target.dispatchEvent(new MouseEvent('click', opts));
            if (typeof target.click === 'function') {
                target.click();
            }

            return {
                success: true,
                tag: target.tagName.toLowerCase(),
                text: (target.innerText || "").trim().substring(0, 50)
            };
        },

        type: function(selector, text, clearFirst = true, pressEnter = false) {
            const el = document.querySelector(selector);
            if (!el) return { success: false, error: `Input element not found: ${selector}` };

            el.focus();

            if (clearFirst) {
                el.value = "";
                el.textContent = "";
            }

            if (el.isContentEditable) {
                document.execCommand('insertText', false, text);
            } else {
                el.value = (clearFirst ? "" : el.value) + text;
            }

            el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, data: text, inputType: 'insertText' }));
            el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, data: text, inputType: 'insertText' }));
            el.dispatchEvent(new Event('change', { bubbles: true }));

            if (pressEnter) {
                const enterOpts = { bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', keyCode: 13, which: 13 };
                el.dispatchEvent(new KeyboardEvent('keydown', enterOpts));
                el.dispatchEvent(new KeyboardEvent('keypress', enterOpts));
                el.dispatchEvent(new KeyboardEvent('keyup', enterOpts));

                const form = el.closest('form');
                if (form && typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                }
            }

            return { success: true, selector: selector, value: el.value || el.textContent };
        },

        scroll: function(x = 0, y = 0, selector = null) {
            if (selector) {
                const el = document.querySelector(selector);
                if (el) {
                    el.scrollBy(x, y);
                    return { success: true, scrollTop: el.scrollTop, scrollLeft: el.scrollLeft };
                }
                return { success: false, error: `Selector not found: ${selector}` };
            }
            window.scrollBy(x, y);
            return { success: true, scrollX: window.scrollX, scrollY: window.scrollY };
        }
    };

    console.log("[AgyBrowserBridge v4.0] Universal Web Sniffer & Bridge Loaded Successfully.");
})();
