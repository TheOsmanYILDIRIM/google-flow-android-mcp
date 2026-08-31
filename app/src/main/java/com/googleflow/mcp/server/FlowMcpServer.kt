package com.googleflow.mcp.server

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.googleflow.mcp.engine.FlowScraperEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class FlowMcpServer(private val engine: FlowScraperEngine, private val port: Int = 8765) {

    private var server: ApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) {
                gson()
            }
            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                anyHost()
            }

            routing {
                // MCP SSE endpoint
                get("/sse") {
                    call.respondText(
                        "event: endpoint\ndata: /mcp\n\n",
                        ContentType.Text.EventStream
                    )
                }

                // Health & Status
                get("/api/status") {
                    val auth = engine.bridge.authState.value
                    val url = engine.bridge.currentUrl.value
                    val response = mapOf(
                        "status" to "ok",
                        "isLoggedIn" to auth,
                        "currentUrl" to url,
                        "port" to port
                    )
                    call.respond(response)
                }

                // MCP JSON-RPC 2.0 Handlers
                post("/mcp") {
                    val body = call.receiveText()
                    val jsonRpc = try {
                        gson.fromJson(body, JsonObject::class.java)
                    } catch (e: Exception) {
                        null
                    }

                    if (jsonRpc == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
                        return@post
                    }

                    val id = jsonRpc.get("id")
                    val method = jsonRpc.get("method")?.asString

                    when (method) {
                        "initialize" -> {
                            val result = JsonObject().apply {
                                addProperty("protocolVersion", "2024-11-05")
                                val serverInfo = JsonObject().apply {
                                    addProperty("name", "google-flow-android-mcp")
                                    addProperty("version", "1.0.0")
                                }
                                add("serverInfo", serverInfo)
                                val capabilities = JsonObject().apply {
                                    add("tools", JsonObject())
                                }
                                add("capabilities", capabilities)
                            }
                            call.respond(createRpcSuccess(id, result))
                        }

                        "tools/list" -> {
                            val tools = getToolDefinitions()
                            val result = JsonObject().apply {
                                add("tools", tools)
                            }
                            call.respond(createRpcSuccess(id, result))
                        }

                        "tools/call" -> {
                            val params = jsonRpc.getAsJsonObject("params")
                            val toolName = params.get("name").asString
                            val arguments = params.getAsJsonObject("arguments") ?: JsonObject()

                            val result = handleToolCall(toolName, arguments)
                            call.respond(createRpcSuccess(id, result))
                        }

                        else -> {
                            call.respond(createRpcError(id, -32601, "Method not found: $method"))
                        }
                    }
                }

                // REST API direct endpoints for lightweight scripting
                post("/api/generate") {
                    val req = try {
                        gson.fromJson(call.receiveText(), JsonObject::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    val prompt = req?.get("prompt")?.asString ?: ""
                    val customPath = req?.get("outputPath")?.asString

                    val result = executeGenerateImage(prompt, customPath)
                    call.respond(result)
                }

                post("/api/generate-with-reference") {
                    val req = gson.fromJson(call.receiveText(), JsonObject::class.java)
                    val prompt = req.get("prompt")?.asString ?: ""
                    val imagePath = req.get("imagePath")?.asString ?: ""
                    val customPath = req.get("outputPath")?.asString

                    val result = executeGenerateWithReference(prompt, imagePath, customPath)
                    call.respond(result)
                }

                post("/api/video") {
                    val req = gson.fromJson(call.receiveText(), JsonObject::class.java)
                    val prompt = req.get("prompt")?.asString ?: ""
                    val customPath = req.get("outputPath")?.asString

                    val result = executeGenerateVideo(prompt, customPath)
                    call.respond(result)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    private fun getToolDefinitions(): JsonArray {
        val list = JsonArray()

        list.add(JsonObject().apply {
            addProperty("name", "flow_status")
            addProperty("description", "Checks authentication, account status, and Google Flow ready state")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_generate_image")
            addProperty("description", "Generates an AI image using Google Flow Imagen model from text prompt")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Detailed text prompt for image generation")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Optional local output file path to save the generated image")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply { add("prompt") }
                add("required", req)
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_generate_image_with_references")
            addProperty("description", "Generates a new AI image conditioned on a reference image (Image-to-Image / Style Transfer)")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Prompt describing transformation or new image")
                    })
                    add("imagePath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Absolute local path to the reference image on phone/Termux")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Optional local destination path for output")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply {
                    add("prompt")
                    add("imagePath")
                }
                add("required", req)
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_generate_video")
            addProperty("description", "Generates an AI video using Google Veo-2 model from text prompt")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Text prompt for video generation")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Optional local path to save the MP4 video")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply { add("prompt") }
                add("required", req)
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_discover_ui")
            addProperty("description", "Inspects current Google Flow page DOM elements for troubleshooting")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            })
        })

        return list
    }

    private suspend fun handleToolCall(toolName: String, args: JsonObject): JsonObject {
        val content = JsonArray()
        val textObj = JsonObject()
        textObj.addProperty("type", "text")

        when (toolName) {
            "flow_status" -> {
                val auth = engine.bridge.authState.value
                val url = engine.bridge.currentUrl.value
                textObj.addProperty("text", "Status: ${if (auth) "Logged In & Ready" else "Needs Login"}\nURL: $url")
            }
            "flow_generate_image" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateImage(prompt, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_generate_image_with_references" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val imgPath = args.get("imagePath")?.asString ?: ""
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateWithReference(prompt, imgPath, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_generate_video" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateVideo(prompt, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_discover_ui" -> {
                var uiData = "{}"
                engine.discoverUi { uiData = it }
                textObj.addProperty("text", uiData)
            }
            else -> {
                textObj.addProperty("text", "Unknown tool: $toolName")
            }
        }

        content.add(textObj)
        return JsonObject().apply {
            add("content", content)
        }
    }

    private suspend fun executeGenerateImage(prompt: String, outputPath: String?): Map<String, Any> {
        var taskId = ""
        engine.generateImage(prompt) { taskId = it }

        val result = withTimeoutOrNull(120000) {
            engine.bridge.generationEvents.first { it.taskId == taskId }
        }

        return if (result != null && result.success) {
            val file = try {
                engine.downloadMedia(result.mediaUrl, outputPath)
            } catch (e: Exception) {
                null
            }
            mapOf(
                "success" to true,
                "taskId" to taskId,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (file?.absolutePath ?: ""),
                "message" to "Image generated successfully"
            )
        } else {
            mapOf(
                "success" to false,
                "taskId" to taskId,
                "error" to (result?.errorMessage ?: "Timeout waiting for image generation")
            )
        }
    }

    private suspend fun executeGenerateWithReference(prompt: String, imagePath: String, outputPath: String?): Map<String, Any> {
        val file = File(imagePath)
        if (!file.exists()) {
            return mapOf("success" to false, "error" to "Reference image not found: $imagePath")
        }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"

        var taskId = ""
        engine.generateWithReference(prompt, base64, mimeType, file.name) { taskId = it }

        val result = withTimeoutOrNull(150000) {
            engine.bridge.generationEvents.first { it.taskId == taskId }
        }

        return if (result != null && result.success) {
            val savedFile = try {
                engine.downloadMedia(result.mediaUrl, outputPath)
            } catch (e: Exception) {
                null
            }
            mapOf(
                "success" to true,
                "taskId" to taskId,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (savedFile?.absolutePath ?: ""),
                "message" to "Image-to-image generated successfully"
            )
        } else {
            mapOf(
                "success" to false,
                "taskId" to taskId,
                "error" to (result?.errorMessage ?: "Timeout waiting for reference generation")
            )
        }
    }

    private suspend fun executeGenerateVideo(prompt: String, outputPath: String?): Map<String, Any> {
        var taskId = ""
        engine.generateVideo(prompt) { taskId = it }

        val result = withTimeoutOrNull(300000) {
            engine.bridge.generationEvents.first { it.taskId == taskId }
        }

        return if (result != null && result.success) {
            val file = try {
                engine.downloadMedia(result.mediaUrl, outputPath)
            } catch (e: Exception) {
                null
            }
            mapOf(
                "success" to true,
                "taskId" to taskId,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (file?.absolutePath ?: ""),
                "message" to "Video generated successfully"
            )
        } else {
            mapOf(
                "success" to false,
                "taskId" to taskId,
                "error" to (result?.errorMessage ?: "Timeout waiting for video generation")
            )
        }
    }

    private fun createRpcSuccess(id: Any?, result: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            when (id) {
                is com.google.gson.JsonElement -> add("id", id)
                is Number -> addProperty("id", id)
                is String -> addProperty("id", id)
                else -> addProperty("id", 1)
            }
            add("result", result)
        }
    }

    private fun createRpcError(id: Any?, code: Int, message: String): JsonObject {
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            when (id) {
                is com.google.gson.JsonElement -> add("id", id)
                is Number -> addProperty("id", id)
                is String -> addProperty("id", id)
                else -> addProperty("id", 1)
            }
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
    }
}
