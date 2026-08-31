package com.googleflow.mcp.server

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * High-Performance Native Java POSIX Thread HTTP Server
 * Uses raw sockets and dedicated OS threads so it NEVER freezes in background.
 */
class FlowMcpServer(
    private val engine: FlowScraperEngine,
    private val port: Int = 8765
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    fun start() {
        if (isRunning) return
        isRunning = true

        threadPool.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", port))
                }
                engine.bridge.log("Native POSIX HTTP Server listening on http://127.0.0.1:$port")

                while (isRunning && serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    engine.bridge.log("Server socket error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1].split("?")[0]

            // Read Headers
            var contentLength = 0
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Read Body
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(chars, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(chars, 0, read)
            } else ""

            handleRoute(method, path, body, output)
        } catch (e: Exception) {
            // Socket handled
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun handleRoute(method: String, path: String, body: String, output: OutputStream) {
        when {
            method == "GET" && path == "/" -> {
                sendResponse(output, 200, "text/plain", "Google Flow Android Native MCP Server (Active)")
            }

            method == "GET" && path == "/api/status" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.checkStatus { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                }
                val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                sendResponse(output, 200, "application/json", result)
            }

            method == "GET" && path == "/api/cookies" -> {
                val deferred = CompletableDeferred<Map<String, Any>>()
                mainHandler.post {
                    val cookieManager = CookieManager.getInstance()
                    val labsCookies = cookieManager.getCookie("https://labs.google") ?: ""
                    val googleCookies = cookieManager.getCookie("https://google.com") ?: ""
                    val accountsCookies = cookieManager.getCookie("https://accounts.google.com") ?: ""
                    
                    val combined = listOf(labsCookies, googleCookies, accountsCookies)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")

                    deferred.complete(mapOf(
                        "success" to true,
                        "cookieHeader" to combined,
                        "labsCookies" to labsCookies,
                        "googleCookies" to googleCookies,
                        "accountsCookies" to accountsCookies
                    ))
                }
                val result = runBlocking { withTimeoutOrNull(3000) { deferred.await() } ?: mapOf("success" to false) }
                sendResponse(output, 200, "application/json", gson.toJson(result))
            }

            method == "POST" && path == "/api/cookies" -> {
                val json = gson.fromJson(body, JsonObject::class.java)
                val cookies = json.get("cookies")?.asString
                if (!cookies.isNullOrBlank()) {
                    engine.importCookies(cookies)
                    sendResponse(output, 200, "application/json", gson.toJson(mapOf("success" to true)))
                } else {
                    sendResponse(output, 400, "application/json", gson.toJson(mapOf("error" to "No cookies")))
                }
            }

            method == "GET" && path == "/api/page-source" -> {
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
                sendResponse(output, 200, "text/html", html)
            }

            method == "POST" && path == "/api/eval" -> {
                val json = gson.fromJson(body, JsonObject::class.java)
                val script = json.get("script")?.asString ?: ""
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.webView?.evaluateJavascript(script) { result ->
                        deferred.complete(result ?: "null")
                    } ?: deferred.complete("null")
                }
                val evalResult = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{\"status\":\"executed\"}" }
                sendResponse(output, 200, "application/json", evalResult)
            }

            method == "POST" && path == "/api/navigate" -> {
                val json = gson.fromJson(body, JsonObject::class.java)
                val url = json.get("url")?.asString ?: ""
                if (url.isNotBlank()) {
                    mainHandler.post {
                        engine.webView?.loadUrl(url)
                    }
                    sendResponse(output, 200, "application/json", gson.toJson(mapOf("success" to true, "navigatedTo" to url)))
                } else {
                    sendResponse(output, 400, "application/json", gson.toJson(mapOf("error" to "URL required")))
                }
            }

            method == "GET" && path == "/api/dom-dump" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.dumpDom { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                }
                val result = runBlocking { withTimeoutOrNull(4000) { deferred.await() } ?: "{}" }
                sendResponse(output, 200, "application/json", result)
            }

            method == "POST" && path == "/mcp" -> {
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
                                    addProperty("version", "3.9.0")
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
                sendResponse(output, 200, "application/json", responseJson)
            }

            else -> {
                sendResponse(output, 404, "text/plain", "Not Found")
            }
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }
}
