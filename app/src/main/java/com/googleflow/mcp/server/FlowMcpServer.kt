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
                get("/sse") {
                    call.respondText(
                        "event: endpoint\ndata: /mcp\n\n",
                        ContentType.Text.EventStream
                    )
                }

                get("/api/status") {
                    val auth = engine.bridge.authState.value
                    val url = engine.bridge.currentUrl.value
                    val response = mapOf(
                        "status" to "ok",
                        "isLoggedIn" to auth,
                        "currentUrl" to url,
                        "supportedModels" to listOf("nano-banana-2", "nano-banana", "veo-3.1"),
                        "supportedAspectRatios" to listOf("1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2", "21:9"),
                        "maxOutputsCount" to 4,
                        "port" to port
                    )
                    call.respond(response)
                }

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
                                    addProperty("version", "2.0.0")
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

                // REST API direct endpoints
                post("/api/generate") {
                    val req = try {
                        gson.fromJson(call.receiveText(), JsonObject::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    val prompt = req?.get("prompt")?.asString ?: ""
                    val model = req?.get("model")?.asString ?: "nano-banana-2"
                    val aspectRatio = req?.get("aspectRatio")?.asString ?: "1:1"
                    val count = req?.get("count")?.asInt ?: 1
                    val customPath = req?.get("outputPath")?.asString

                    val result = executeGenerateImage(prompt, model, aspectRatio, count, customPath)
                    call.respond(result)
                }

                post("/api/generate-with-reference") {
                    val req = gson.fromJson(call.receiveText(), JsonObject::class.java)
                    val prompt = req.get("prompt")?.asString ?: ""
                    val imagePath = req.get("imagePath")?.asString ?: ""
                    val model = req.get("model")?.asString ?: "nano-banana-2"
                    val aspectRatio = req.get("aspectRatio")?.asString ?: "1:1"
                    val count = req.get("count")?.asInt ?: 1
                    val customPath = req.get("outputPath")?.asString

                    val result = executeGenerateWithReference(prompt, imagePath, model, aspectRatio, count, customPath)
                    call.respond(result)
                }

                post("/api/video") {
                    val req = gson.fromJson(call.receiveText(), JsonObject::class.java)
                    val prompt = req.get("prompt")?.asString ?: ""
                    val model = req.get("model")?.asString ?: "veo-3.1"
                    val aspectRatio = req.get("aspectRatio")?.asString ?: "16:9"
                    val customPath = req.get("outputPath")?.asString

                    val result = executeGenerateVideo(prompt, model, aspectRatio, customPath)
                    call.respond(result)
                }

                get("/api/projects") {
                    var projectsJson = "[]"
                    engine.listProjects { projectsJson = it }
                    call.respondText(projectsJson, ContentType.Application.Json)
                }

                post("/api/projects") {
                    val req = gson.fromJson(call.receiveText(), JsonObject::class.java)
                    val name = req.get("name")?.asString ?: "New Project"
                    engine.createProject(name)
                    call.respond(mapOf("success" to true, "name" to name))
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
            addProperty("description", "Checks auth status, active models (Nano Banana 2, Veo 3.1), and credit balance")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_generate_image")
            addProperty("description", "Generates AI images using Nano Banana 2 with custom aspect ratios and multi-output batch count (1x-4x)")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Detailed text prompt for image generation")
                    })
                    add("model", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Image model: 'nano-banana-2' (default) or 'nano-banana'")
                    })
                    add("aspect_ratio", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Aspect ratio: '1:1', '16:9', '9:16', '4:3', '3:4', '2:3', '3:2', '21:9'")
                    })
                    add("count", JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "Number of variations to generate simultaneously: 1, 2, 3, or 4")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Optional local destination path to save output image")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply { add("prompt") }
                add("required", req)
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_generate_image_with_references")
            addProperty("description", "Generates new AI images conditioned on a reference image with aspect ratio and multi-count options")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Transformation prompt")
                    })
                    add("imagePath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Absolute path to reference image")
                    })
                    add("model", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Model: 'nano-banana-2' or 'nano-banana'")
                    })
                    add("aspect_ratio", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Aspect ratio: '1:1', '16:9', '9:16', '4:3', '3:4'")
                    })
                    add("count", JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "Number of outputs (1-4)")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Optional local destination path")
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
            addProperty("description", "Generates AI video using Veo 3.1 model from text prompt")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("prompt", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Video description prompt")
                    })
                    add("model", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Video model: 'veo-3.1'")
                    })
                    add("aspect_ratio", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Aspect ratio: '16:9' or '9:16'")
                    })
                    add("outputPath", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Destination MP4 file path")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply { add("prompt") }
                add("required", req)
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_list_projects")
            addProperty("description", "Lists all user projects and folders in Google Flow")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            })
        })

        list.add(JsonObject().apply {
            addProperty("name", "flow_create_project")
            addProperty("description", "Creates a new category/project workspace in Google Flow")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    add("name", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "Name of the new project")
                    })
                }
                add("properties", props)
                val req = JsonArray().apply { add("name") }
                add("required", req)
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
                textObj.addProperty("text", "Status: ${if (auth) "Logged In & Ready" else "Needs Login"}\nURL: $url\nModels: Nano Banana 2, Veo 3.1\nMax Batch Count: 4x")
            }
            "flow_generate_image" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val model = args.get("model")?.asString ?: "nano-banana-2"
                val ratio = args.get("aspect_ratio")?.asString ?: "1:1"
                val count = args.get("count")?.asInt ?: 1
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateImage(prompt, model, ratio, count, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_generate_image_with_references" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val imgPath = args.get("imagePath")?.asString ?: ""
                val model = args.get("model")?.asString ?: "nano-banana-2"
                val ratio = args.get("aspect_ratio")?.asString ?: "1:1"
                val count = args.get("count")?.asInt ?: 1
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateWithReference(prompt, imgPath, model, ratio, count, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_generate_video" -> {
                val prompt = args.get("prompt")?.asString ?: ""
                val model = args.get("model")?.asString ?: "veo-3.1"
                val ratio = args.get("aspect_ratio")?.asString ?: "16:9"
                val outputPath = args.get("outputPath")?.asString
                val res = executeGenerateVideo(prompt, model, ratio, outputPath)
                textObj.addProperty("text", gson.toJson(res))
            }
            "flow_list_projects" -> {
                var projects = "[]"
                engine.listProjects { projects = it }
                textObj.addProperty("text", projects)
            }
            "flow_create_project" -> {
                val name = args.get("name")?.asString ?: "Project"
                engine.createProject(name)
                textObj.addProperty("text", "Project creation requested: $name")
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

    private suspend fun executeGenerateImage(
        prompt: String,
        model: String,
        aspectRatio: String,
        count: Int,
        outputPath: String?
    ): Map<String, Any> {
        var taskId = ""
        engine.generateImage(prompt, model, aspectRatio, count) { taskId = it }

        val result = withTimeoutOrNull(180000) {
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
                "model" to model,
                "aspectRatio" to aspectRatio,
                "count" to count,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (file?.absolutePath ?: ""),
                "message" to "Image generated with $model ($aspectRatio)"
            )
        } else {
            mapOf(
                "success" to false,
                "taskId" to taskId,
                "error" to (result?.errorMessage ?: "Timeout waiting for image generation")
            )
        }
    }

    private suspend fun executeGenerateWithReference(
        prompt: String,
        imagePath: String,
        model: String,
        aspectRatio: String,
        count: Int,
        outputPath: String?
    ): Map<String, Any> {
        val file = File(imagePath)
        if (!file.exists()) {
            return mapOf("success" to false, "error" to "Reference image not found: $imagePath")
        }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mimeType = if (file.name.endsWith(".png", true)) "image/png" else "image/jpeg"

        var taskId = ""
        engine.generateWithReference(prompt, base64, mimeType, file.name, model, aspectRatio, count) { taskId = it }

        val result = withTimeoutOrNull(200000) {
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
                "model" to model,
                "aspectRatio" to aspectRatio,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (savedFile?.absolutePath ?: ""),
                "message" to "Reference generation succeeded with $model"
            )
        } else {
            mapOf(
                "success" to false,
                "taskId" to taskId,
                "error" to (result?.errorMessage ?: "Timeout waiting for reference generation")
            )
        }
    }

    private suspend fun executeGenerateVideo(
        prompt: String,
        model: String,
        aspectRatio: String,
        outputPath: String?
    ): Map<String, Any> {
        var taskId = ""
        engine.generateVideo(prompt, model, aspectRatio) { taskId = it }

        val result = withTimeoutOrNull(360000) {
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
                "model" to model,
                "aspectRatio" to aspectRatio,
                "mediaUrl" to result.mediaUrl,
                "localPath" to (file?.absolutePath ?: ""),
                "message" to "Video generated successfully with $model"
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
