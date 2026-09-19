package com.googleflow.mcp.server

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Universal Android Browser Bridge & Flow MCP Embedded Server
 * Full REST API + Stdio/HTTP JSON-RPC MCP 2.0 Engine.
 */
class FlowMcpServer(
    private val engine: FlowScraperEngine,
    port: Int = 8765
) : NanoHTTPD("127.0.0.1", port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        val response = when (uri) {
            "/" -> {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    "Universal Android Browser Bridge & Google Flow MCP Server (Active on Port 8765)"
                )
            }

            // -----------------------------------------------------------------
            // STATUS & HEALTH
            // -----------------------------------------------------------------
            "/api/status" -> {
                val currentUrl = engine.bridge.currentUrl.value
                val pageTitle = engine.bridge.pageTitle.value
                val isAuth = engine.bridge.authState.value
                val trafficCount = engine.bridge.trafficCount
                val consoleCount = engine.bridge.consoleCount

                val res = mapOf(
                    "status" to "active",
                    "version" to "4.0.0",
                    "isLoggedIn" to isAuth,
                    "currentUrl" to currentUrl,
                    "pageTitle" to pageTitle,
                    "trafficCount" to trafficCount,
                    "consoleCount" to consoleCount,
                    "userAgent" to engine.currentUserAgent,
                    "supportedModels" to listOf("Nano Banana 2", "Veo 3.1 - Fast", "Veo 3.1 - Quality", "Imagen 3"),
                    "supportedAspectRatios" to listOf("1:1", "16:9", "9:16", "4:3", "3:4")
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(res))
            }

            // -----------------------------------------------------------------
            // NAVIGATION & CONTROLS
            // -----------------------------------------------------------------
            "/api/navigate" -> {
                val body = parseBodyJson(session)
                val url = body.get("url")?.asString ?: ""
                val ua = body.get("userAgent")?.asString
                if (url.isNotBlank()) {
                    engine.navigate(url, ua)
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true, "url" to url)))
                } else {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(mapOf("error" to "URL required")))
                }
            }

            "/api/reload" -> {
                engine.reload()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            "/api/back" -> {
                engine.goBack()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            "/api/forward" -> {
                engine.goForward()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            // -----------------------------------------------------------------
            // JAVASCRIPT EVALUATION
            // -----------------------------------------------------------------
            "/api/eval" -> {
                val body = parseBodyJson(session)
                val script = body.get("script")?.asString ?: ""
                val deferred = CompletableDeferred<String>()
                engine.evaluateJs(script) { result ->
                    deferred.complete(result)
                }
                val evalResult = runBlocking { withTimeoutOrNull(6000) { deferred.await() } ?: "{\"status\":\"timeout\"}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", evalResult)
            }

            // -----------------------------------------------------------------
            // DOM & INTERACTION
            // -----------------------------------------------------------------
            "/api/dom" -> {
                val format = params["format"] ?: "interactive"
                val selector = params["selector"]
                val deferred = CompletableDeferred<String>()
                engine.getDom(format, selector) { result ->
                    deferred.complete(result)
                }
                val domResult = runBlocking { withTimeoutOrNull(6000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", domResult)
            }

            "/api/click" -> {
                val body = parseBodyJson(session)
                val selector = body.get("selector")?.asString
                val text = body.get("text")?.asString
                val deferred = CompletableDeferred<String>()
                engine.clickElement(selector, text) { result ->
                    deferred.complete(result)
                }
                val clickResult = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", clickResult)
            }

            "/api/type" -> {
                val body = parseBodyJson(session)
                val selector = body.get("selector")?.asString ?: ""
                val text = body.get("text")?.asString ?: ""
                val clear = body.get("clear")?.asBoolean ?: true
                val enter = body.get("enter")?.asBoolean ?: false
                val deferred = CompletableDeferred<String>()
                engine.typeText(selector, text, clear, enter) { result ->
                    deferred.complete(result)
                }
                val typeResult = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", typeResult)
            }

            "/api/screenshot" -> {
                val deferred = CompletableDeferred<Triple<String?, String?, String?>>()
                engine.captureScreenshot { path, base64, error ->
                    deferred.complete(Triple(path, base64, error))
                }
                val result = runBlocking { withTimeoutOrNull(5000) { deferred.await() } }
                if (result != null && result.third == null) {
                    val res = mapOf(
                        "success" to true,
                        "path" to result.first,
                        "base64" to result.second
                    )
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(res))
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", gson.toJson(mapOf("success" to false, "error" to (result?.third ?: "Timeout"))))
                }
            }

            // -----------------------------------------------------------------
            // NETWORK TRAFFIC SNIFFER
            // -----------------------------------------------------------------
            "/api/traffic" -> {
                val filter = params["filter"]
                val limit = params["limit"]?.toIntOrNull() ?: 50
                val traffic = engine.bridge.getNetworkTraffic(filter, limit)
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(traffic))
            }

            "/api/traffic/clear" -> {
                engine.bridge.clearNetworkTraffic()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            // -----------------------------------------------------------------
            // CONSOLE LOGS
            // -----------------------------------------------------------------
            "/api/console" -> {
                val level = params["level"]
                val limit = params["limit"]?.toIntOrNull() ?: 50
                val logs = engine.bridge.getConsoleLogs(level, limit)
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(logs))
            }

            "/api/console/clear" -> {
                engine.bridge.clearConsoleLogs()
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            // -----------------------------------------------------------------
            // COOKIES & STORAGE
            // -----------------------------------------------------------------
            "/api/cookies" -> {
                if (method == Method.GET) {
                    val targetUrl = params["url"] ?: "https://labs.google"
                    val cookieManager = CookieManager.getInstance()
                    val cookieHeader = cookieManager.getCookie(targetUrl) ?: ""
                    val res = mapOf(
                        "success" to true,
                        "url" to targetUrl,
                        "cookieHeader" to cookieHeader
                    )
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(res))
                } else if (method == Method.POST) {
                    val body = parseBodyJson(session)
                    val cookies = body.get("cookies")?.asString
                    val targetDomain = body.get("url")?.asString ?: "https://labs.google"
                    if (!cookies.isNullOrBlank()) {
                        engine.importCookies(cookies, targetDomain)
                        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(mapOf("error" to "No cookies provided")))
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
                }
            }

            "/api/cookies/clear" -> {
                val deferred = CompletableDeferred<Boolean>()
                engine.clearAllCookies { deferred.complete(true) }
                runBlocking { deferred.await() }
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
            }

            "/api/page-source" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.webView?.evaluateJavascript("document.documentElement.outerHTML") { htmlResult ->
                        val unescaped = if (htmlResult != null && htmlResult.startsWith("\"") && htmlResult.endsWith("\"")) {
                            try {
                                gson.fromJson(htmlResult, String::class.java)
                            } catch (e: Exception) {
                                htmlResult
                            }
                        } else htmlResult ?: ""
                        deferred.complete(unescaped)
                    } ?: deferred.complete("")
                }
                val html = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "" }
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }

            "/api/dom-dump" -> {
                val deferred = CompletableDeferred<String>()
                engine.dumpDom { jsonStr -> deferred.complete(jsonStr) }
                val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
            }

            // -----------------------------------------------------------------
            // JSON-RPC MCP ENDPOINT
            // -----------------------------------------------------------------
            "/mcp" -> {
                val rpcReq = parseBodyJson(session)
                val id = rpcReq.get("id")?.asInt ?: 1
                val rpcMethod = rpcReq.get("method")?.asString ?: ""

                val responseJson = when (rpcMethod) {
                    "initialize" -> {
                        val res = JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            addProperty("id", id)
                            add("result", JsonObject().apply {
                                addProperty("protocolVersion", "2024-11-05")
                                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                                add("serverInfo", JsonObject().apply {
                                    addProperty("name", "universal-android-browser-bridge")
                                    addProperty("version", "4.0.0")
                                })
                            })
                        }
                        gson.toJson(res)
                    }

                    "tools/list" -> {
                        val tools = listOf(
                            mapOf(
                                "name" to "browser_status",
                                "description" to "Get active Android browser status, loaded URL, page title, traffic count and stats",
                                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                            ),
                            mapOf(
                                "name" to "browser_navigate",
                                "description" to "Navigate the Android browser to any URL",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "url" to mapOf("type" to "string", "description" to "Target URL (e.g. https://example.com)"),
                                        "userAgent" to mapOf("type" to "string", "description" to "Optional custom User-Agent")
                                    ),
                                    "required" to listOf("url")
                                )
                            ),
                            mapOf(
                                "name" to "browser_evaluate_js",
                                "description" to "Execute arbitrary JavaScript inside the active page context and return the result",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "script" to mapOf("type" to "string", "description" to "JavaScript code to execute")
                                    ),
                                    "required" to listOf("script")
                                )
                            ),
                            mapOf(
                                "name" to "browser_get_dom",
                                "description" to "Extract page DOM: interactive UI elements tree, readable text, or raw HTML",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "format" to mapOf("type" to "string", "enum" to listOf("interactive", "text", "html"), "description" to "Extraction format (default: interactive)"),
                                        "selector" to mapOf("type" to "string", "description" to "Optional CSS selector to scope extraction")
                                    )
                                )
                            ),
                            mapOf(
                                "name" to "browser_click",
                                "description" to "Click a button, link, or interactive element by CSS selector or text match",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "selector" to mapOf("type" to "string", "description" to "CSS selector of element"),
                                        "text" to mapOf("type" to "string", "description" to "Visible text match fallback")
                                    )
                                )
                            ),
                            mapOf(
                                "name" to "browser_type",
                                "description" to "Type text into an input field or contenteditable element with synthetic events",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "selector" to mapOf("type" to "string", "description" to "CSS selector of input field"),
                                        "text" to mapOf("type" to "string", "description" to "Text string to type"),
                                        "clear" to mapOf("type" to "boolean", "description" to "Clear existing content before typing (default: true)"),
                                        "enter" to mapOf("type" to "boolean", "description" to "Press Enter after typing to submit (default: false)")
                                    ),
                                    "required" to listOf("selector", "text")
                                )
                            ),
                            mapOf(
                                "name" to "browser_get_network_traffic",
                                "description" to "Read all intercepted HTTP/HTTPS fetch and XHR requests & responses (headers, payloads, status)",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "filter" to mapOf("type" to "string", "description" to "Filter by URL keyword or method"),
                                        "limit" to mapOf("type" to "integer", "description" to "Max number of items to return (default: 50)")
                                    )
                                )
                            ),
                            mapOf(
                                "name" to "browser_get_console_logs",
                                "description" to "Read captured console.log, warn, error, and uncaught exception messages from page",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "level" to mapOf("type" to "string", "enum" to listOf("log", "info", "warn", "error"), "description" to "Filter by log level"),
                                        "limit" to mapOf("type" to "integer", "description" to "Max number of logs to return (default: 50)")
                                    )
                                )
                            ),
                            mapOf(
                                "name" to "browser_screenshot",
                                "description" to "Capture high-resolution screenshot of current page view and save to Documents/BrowserBridge",
                                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                            ),
                            mapOf(
                                "name" to "browser_get_cookies",
                                "description" to "Extract active session cookies from Android CookieManager for a domain",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "url" to mapOf("type" to "string", "description" to "Target domain URL (default: https://labs.google)")
                                    )
                                )
                            ),
                            mapOf(
                                "name" to "browser_set_cookies",
                                "description" to "Inject cookies into Android CookieManager for authenticated sessions",
                                "inputSchema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "cookies" to mapOf("type" to "string", "description" to "Semicolon-separated cookie string or header"),
                                        "url" to mapOf("type" to "string", "description" to "Target domain URL")
                                    ),
                                    "required" to listOf("cookies")
                                )
                            )
                        )
                        val res = JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            addProperty("id", id)
                            add("result", JsonObject().apply {
                                add("tools", gson.toJsonTree(tools))
                            })
                        }
                        gson.toJson(res)
                    }

                    else -> {
                        val errRes = JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            addProperty("id", id)
                            add("error", JsonObject().apply {
                                addProperty("code", -32601)
                                addProperty("message", "Method not found: $rpcMethod")
                            })
                        }
                        gson.toJson(errRes)
                    }
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        addCorsHeaders(response)
        return response
    }

    private fun parseBodyJson(session: IHTTPSession): JsonObject {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val body = map["postData"] ?: ""
            gson.fromJson(body, JsonObject::class.java) ?: JsonObject()
        } catch (e: Exception) {
            JsonObject()
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }
}
