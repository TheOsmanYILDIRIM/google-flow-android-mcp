package com.googleflow.mcp.server

import android.util.Base64
import com.googleflow.mcp.engine.FlowScraperEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class FlowMcpServer(
    private val engine: FlowScraperEngine,
    private val port: Int = 8765
) {
    private var server: ApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/") {
                    call.respondText(
                        "Google Flow Android MCP Server v2.4 (Veo 3.1 & Nano Banana 2 Active)",
                        ContentType.Text.Plain
                    )
                }

                get("/api/status") {
                    val deferred = CompletableDeferred<String>()
                    engine.checkStatus { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                get("/api/dom-dump") {
                    val deferred = CompletableDeferred<String>()
                    engine.dumpDom { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                post("/api/cookies") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val cookies = json.get("cookies")?.asString
                    if (!cookies.isNullOrBlank()) {
                        engine.importCookies(cookies)
                        call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Cookies imported"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No cookies provided"))
                    }
                }

                get("/api/projects") {
                    val deferred = CompletableDeferred<String>()
                    engine.listProjects { jsonStr ->
                        deferred.complete(jsonStr)
                    }
                    val result = deferred.await()
                    call.respondText(result, ContentType.Application.Json)
                }

                post("/api/projects") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val name = json.get("name")?.asString ?: "New Project"
                    engine.createProject(name)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "created", "name" to name))
                }

                post("/api/generate") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "nano-banana-2"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "1:1"
                    val count = json.get("count")?.asInt ?: 1
                    val outputPath = json.get("outputPath")?.asString

                    if (prompt.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Prompt is required"))
                        return@post
                    }

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateImage(prompt, model, aspectRatio, count) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(240000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "localPath" to localFile.absolutePath,
                                    "model" to model,
                                    "aspectRatio" to aspectRatio
                                )
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "downloadError" to e.message
                                )
                            )
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (genResult?.errorMessage ?: "Generation timed out or failed on device"))
                        )
                    }
                }

                post("/api/generate-with-reference") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val imagePath = json.get("imagePath")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "nano-banana-2"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "1:1"
                    val count = json.get("count")?.asInt ?: 1
                    val outputPath = json.get("outputPath")?.asString

                    val refFile = File(imagePath)
                    if (!refFile.exists()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Reference image not found: $imagePath"))
                        return@post
                    }

                    val base64Image = Base64.encodeToString(refFile.readBytes(), Base64.NO_WRAP)
                    val mimeType = if (refFile.name.endsWith(".png", true)) "image/png" else "image/jpeg"

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateWithReference(prompt, base64Image, mimeType, refFile.name, model, aspectRatio, count) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(260000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "localPath" to localFile.absolutePath,
                                    "model" to model,
                                    "aspectRatio" to aspectRatio
                                )
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "downloadError" to e.message
                                )
                            )
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (genResult?.errorMessage ?: "Reference image generation failed or timed out"))
                        )
                    }
                }

                post("/api/video") {
                    val body = call.receiveText()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val prompt = json.get("prompt")?.asString ?: ""
                    val model = json.get("model")?.asString ?: "veo-3.1"
                    val aspectRatio = json.get("aspectRatio")?.asString ?: "16:9"
                    val outputPath = json.get("outputPath")?.asString

                    val deferred = CompletableDeferred<String>()
                    val taskId = engine.generateVideo(prompt, model, aspectRatio) { tId ->
                        deferred.complete(tId)
                    }

                    val genResult = withTimeoutOrNull(400000) {
                        engine.bridge.generationEvents.first { it.taskId == taskId }
                    }

                    if (genResult != null && genResult.success) {
                        val mediaUrl = genResult.mediaUrl
                        try {
                            val localFile = engine.downloadMedia(mediaUrl, outputPath)
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "localPath" to localFile.absolutePath,
                                    "model" to model,
                                    "aspectRatio" to aspectRatio
                                )
                            )
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "taskId" to taskId,
                                    "mediaUrl" to mediaUrl,
                                    "downloadError" to e.message
                                )
                            )
                        }
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (genResult?.errorMessage ?: "Video generation timed out or failed"))
                        )
                    }
                }

                post("/mcp") {
                    val body = call.receiveText()
                    val rpcReq = gson.fromJson(body, JsonObject::class.java)
                    val id = rpcReq.get("id")?.asInt ?: 1
                    val method = rpcReq.get("method")?.asString ?: ""

                    when (method) {
                        "initialize" -> {
                            val res = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                add("result", JsonObject().apply {
                                    addProperty("protocolVersion", "2024-11-05")
                                    add("capabilities", JsonObject().apply {
                                        add("tools", JsonObject())
                                    })
                                    add("serverInfo", JsonObject().apply {
                                        addProperty("name", "google-flow-android-mcp")
                                        addProperty("version", "2.4.0")
                                    })
                                })
                            }
                            call.respondText(gson.toJson(res), ContentType.Application.Json)
                        }
                        "tools/list" -> {
                            val toolsJson = """
                            {
                                "jsonrpc": "2.0",
                                "id": $id,
                                "result": {
                                    "tools": [
                                        {
                                            "name": "flow_status",
                                            "description": "Check Google Flow status, active credits, and supported models (Veo 3.1 & Nano Banana 2)",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_dump_dom",
                                            "description": "Inspect and dump full real-time DOM structure of the current Flow page",
                                            "inputSchema": { "type": "object", "properties": {} }
                                        },
                                        {
                                            "name": "flow_generate_image",
                                            "description": "Generate an image using Google Flow / Nano Banana 2",
                                            "inputSchema": {
                                                "type": "object",
                                                "properties": {
                                                    "prompt": { "type": "string" },
                                                    "model": { "type": "string", "enum": ["nano-banana-2", "nano-banana"], "default": "nano-banana-2" },
                                                    "aspect_ratio": { "type": "string", "enum": ["1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2"], "default": "1:1" },
                                                    "count": { "type": "integer", "enum": [1, 2, 3, 4], "default": 1 },
                                                    "output_path": { "type": "string" }
                                                },
                                                "required": ["prompt"]
                                            }
                                        },
                                        {
                                            "name": "flow_generate_video",
                                            "description": "Generate video using Google Veo 3.1",
                                            "inputSchema": {
                                                "type": "object",
                                                "properties": {
                                                    "prompt": { "type": "string" },
                                                    "aspect_ratio": { "type": "string", "enum": ["16:9", "9:16"], "default": "16:9" },
                                                    "output_path": { "type": "string" }
                                                },
                                                "required": ["prompt"]
                                            }
                                        }
                                    ]
                                }
                            }
                            """.trimIndent()
                            call.respondText(toolsJson, ContentType.Application.Json)
                        }
                        else -> {
                            val errRes = JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                addProperty("id", id)
                                add("error", JsonObject().apply {
                                    addProperty("code", -32601)
                                    addProperty("message", "Method not found: $method")
                                })
                            }
                            call.respondText(gson.toJson(errRes), ContentType.Application.Json)
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
