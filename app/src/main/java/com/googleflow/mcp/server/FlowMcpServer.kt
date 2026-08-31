package com.googleflow.mcp.server

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Standard Production JDK HttpServer
 * Completely standard, thread-safe, 0ms latency, zero socket reset issues.
 */
class FlowMcpServer(
    private val engine: FlowScraperEngine,
    private val port: Int = 8765
) {
    private var server: HttpServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    fun start() {
        if (server != null) return

        try {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                executor = Executors.newCachedThreadPool()

                createContext("/") { exchange ->
                    if (exchange.requestURI.path == "/") {
                        sendResponse(exchange, 200, "text/plain", "Google Flow Android Native MCP Server (Active)")
                    } else {
                        sendResponse(exchange, 404, "text/plain", "Not Found")
                    }
                }

                createContext("/api/cookies") { exchange ->
                    if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
                        val cookieManager = CookieManager.getInstance()
                        val labsCookies = cookieManager.getCookie("https://labs.google") ?: ""
                        val googleCookies = cookieManager.getCookie("https://google.com") ?: ""
                        val accountsCookies = cookieManager.getCookie("https://accounts.google.com") ?: ""
                        
                        val combined = listOf(labsCookies, googleCookies, accountsCookies)
                            .filter { it.isNotBlank() }
                            .joinToString("; ")

                        val res = mapOf(
                            "success" to true,
                            "cookieHeader" to combined,
                            "labsCookies" to labsCookies,
                            "googleCookies" to googleCookies,
                            "accountsCookies" to accountsCookies
                        )
                        sendResponse(exchange, 200, "application/json", gson.toJson(res))
                    } else if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                        val body = exchange.requestBody.bufferedReader().use { it.readText() }
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val cookies = json.get("cookies")?.asString
                        if (!cookies.isNullOrBlank()) {
                            engine.importCookies(cookies)
                            sendResponse(exchange, 200, "application/json", gson.toJson(mapOf("success" to true)))
                        } else {
                            sendResponse(exchange, 400, "application/json", gson.toJson(mapOf("error" to "No cookies")))
                        }
                    }
                }

                createContext("/api/status") { exchange ->
                    val deferred = CompletableDeferred<String>()
                    mainHandler.post {
                        engine.checkStatus { jsonStr ->
                            deferred.complete(jsonStr)
                        }
                    }
                    val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                    sendResponse(exchange, 200, "application/json", result)
                }

                createContext("/api/page-source") { exchange ->
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
                    sendResponse(exchange, 200, "text/html", html)
                }

                createContext("/api/eval") { exchange ->
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val script = json.get("script")?.asString ?: ""
                    val deferred = CompletableDeferred<String>()
                    mainHandler.post {
                        engine.webView?.evaluateJavascript(script) { result ->
                            deferred.complete(result ?: "null")
                        } ?: deferred.complete("null")
                    }
                    val evalResult = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{\"status\":\"timeout\"}" }
                    sendResponse(exchange, 200, "application/json", evalResult)
                }

                createContext("/api/navigate") { exchange ->
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val url = json.get("url")?.asString ?: ""
                    if (url.isNotBlank()) {
                        mainHandler.post {
                            engine.webView?.loadUrl(url)
                        }
                        sendResponse(exchange, 200, "application/json", gson.toJson(mapOf("success" to true, "navigatedTo" to url)))
                    } else {
                        sendResponse(exchange, 400, "application/json", gson.toJson(mapOf("error" to "URL required")))
                    }
                }

                createContext("/api/dom-dump") { exchange ->
                    val deferred = CompletableDeferred<String>()
                    mainHandler.post {
                        engine.dumpDom { jsonStr ->
                            deferred.complete(jsonStr)
                        }
                    }
                    val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                    sendResponse(exchange, 200, "application/json", result)
                }

                createContext("/mcp") { exchange ->
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val rpcReq = gson.fromJson(body, JsonObject::class.java)
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
                                        addProperty("name", "google-flow-android-mcp")
                                        addProperty("version", "4.1.0")
                                    })
                                })
                            }
                            gson.toJson(res)
                        }
                        "tools/list" -> {
                            """
                            {
                                "jsonrpc": "2.0",
                                "id": $id,
                                "result": {
                                    "tools": [
                                        {
                                            "name": "flow_status",
                                            "description": "Check Google Flow status, active credits, and supported models",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_dump_dom",
                                            "description": "Inspect and dump full real-time DOM structure of the current Flow page",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_get_cookies",
                                            "description": "Extract active session cookies from Android app for Python scraping",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        }
                                    ]
                                }
                            }
                            """.trimIndent()
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
                    sendResponse(exchange, 200, "application/json", responseJson)
                }

                start()
                engine.bridge.log("JDK Production HttpServer online at http://127.0.0.1:$port")
            }
        } catch (e: Exception) {
            engine.bridge.log("HttpServer start error: ${e.message}")
        }
    }

    private fun sendResponse(exchange: HttpExchange, statusCode: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { os ->
            os.write(bytes)
        }
    }

    fun stop() {
        try {
            server?.stop(1)
        } catch (e: Exception) {}
        server = null
    }
}
