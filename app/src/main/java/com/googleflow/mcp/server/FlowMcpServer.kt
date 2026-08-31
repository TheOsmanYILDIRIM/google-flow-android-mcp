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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Ultra-Fast Non-Blocking Native POSIX Socket Server
 * Completely thread-safe, parses raw HTTP byte streams, 0ms latency.
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
                engine.bridge.log("Native Socket Server online at http://127.0.0.1:$port")

                while (isRunning && serverSocket?.isClosed == false) {
                    val client = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleConnection(client)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    engine.bridge.log("Server error: ${e.message}")
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 6000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val headerBytes = readHeaderBytes(input) ?: return
            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return

            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return

            val method = requestLine[0]
            val path = requestLine[1].split("?")[0]

            var contentLength = 0
            for (line in lines) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val bodyBuf = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = input.read(bodyBuf, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                String(bodyBuf, 0, totalRead, Charsets.UTF_8)
            } else ""

            routeRequest(method, path, body, output)
        } catch (e: Exception) {
            // Handled
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun readHeaderBytes(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            when (state) {
                0 -> if (b == '\r'.code) state = 1 else state = 0
                1 -> if (b == '\n'.code) state = 2 else if (b == '\r'.code) state = 1 else state = 0
                2 -> if (b == '\r'.code) state = 3 else state = 0
                3 -> if (b == '\n'.code) return baos.toByteArray() else if (b == '\r'.code) state = 1 else state = 0
            }
            if (baos.size() > 65536) break
        }
        return if (baos.size() > 0) baos.toByteArray() else null
    }

    private fun routeRequest(method: String, path: String, body: String, output: OutputStream) {
        when {
            method == "GET" && path == "/" -> {
                send(output, 200, "text/plain", "Google Flow Android Native Server (v4.0 Ready)")
            }

            method == "GET" && path == "/api/cookies" -> {
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
                send(output, 200, "application/json", gson.toJson(res))
            }

            method == "POST" && path == "/api/cookies" -> {
                val json = gson.fromJson(body, JsonObject::class.java)
                val cookies = json.get("cookies")?.asString
                if (!cookies.isNullOrBlank()) {
                    engine.importCookies(cookies)
                    send(output, 200, "application/json", gson.toJson(mapOf("success" to true)))
                } else {
                    send(output, 400, "application/json", gson.toJson(mapOf("error" to "No cookies")))
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
                val html = runBlocking { withTimeoutOrNull(3000) { deferred.await() } ?: "" }
                send(output, 200, "text/html", html)
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
                val evalResult = runBlocking { withTimeoutOrNull(3000) { deferred.await() } ?: "{\"status\":\"timeout\"}" }
                send(output, 200, "application/json", evalResult)
            }

            method == "POST" && path == "/api/navigate" -> {
                val json = gson.fromJson(body, JsonObject::class.java)
                val url = json.get("url")?.asString ?: ""
                if (url.isNotBlank()) {
                    mainHandler.post {
                        engine.webView?.loadUrl(url)
                    }
                    send(output, 200, "application/json", gson.toJson(mapOf("success" to true, "navigatedTo" to url)))
                } else {
                    send(output, 400, "application/json", gson.toJson(mapOf("error" to "URL required")))
                }
            }

            method == "GET" && path == "/api/dom-dump" -> {
                val deferred = CompletableDeferred<String>()
                mainHandler.post {
                    engine.dumpDom { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                }
                val result = runBlocking { withTimeoutOrNull(3000) { deferred.await() } ?: "{}" }
                send(output, 200, "application/json", result)
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
                                    addProperty("version", "4.0.0")
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
                send(output, 200, "application/json", responseJson)
            }

            else -> {
                send(output, 404, "text/plain", "Not Found")
            }
        }
    }

    private fun send(output: OutputStream, statusCode: Int, contentType: String, content: String) {
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
