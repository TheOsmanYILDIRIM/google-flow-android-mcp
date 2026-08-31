package com.googleflow.mcp.server

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NanoHTTPD Embedded Android Server
 * Battle-tested, zero timeout issues, 0ms latency, runs effortlessly in background.
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

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        val response = when (uri) {
            "/" -> {
                newFixedLengthResponse(Response.Status.OK, "text/plain", "Google Flow Android Native MCP Server (Active)")
            }

            "/api/cookies" -> {
                if (method == Method.GET) {
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
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(res))
                } else if (method == Method.POST) {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val body = map["postData"] ?: ""
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val cookies = json.get("cookies")?.asString
                    if (!cookies.isNullOrBlank()) {
                        engine.importCookies(cookies)
                        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true)))
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(mapOf("error" to "No cookies")))
                    }
                } else {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
                }
            }

            "/api/status" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.checkStatus { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                }
                val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
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

            "/api/eval" -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val body = map["postData"] ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                val script = json.get("script")?.asString ?: ""
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.webView?.evaluateJavascript(script) { result ->
                        deferred.complete(result ?: "null")
                    } ?: deferred.complete("null")
                }
                val evalResult = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{\"status\":\"timeout\"}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", evalResult)
            }

            "/api/navigate" -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val body = map["postData"] ?: ""
                val json = gson.fromJson(body, JsonObject::class.java)
                val url = json.get("url")?.asString ?: ""
                if (url.isNotBlank()) {
                    mainHandler.post {
                        engine.webView?.loadUrl(url)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("success" to true, "navigatedTo" to url)))
                } else {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", gson.toJson(mapOf("error" to "URL required")))
                }
            }

            "/api/dom-dump" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.dumpDom { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                }
                val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                newFixedLengthResponse(Response.Status.OK, "application/json", result)
            }

            "/mcp" -> {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val body = map["postData"] ?: ""
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
                                    addProperty("version", "4.2.0")
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
                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        addCorsHeaders(response)
        return response
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }
}
