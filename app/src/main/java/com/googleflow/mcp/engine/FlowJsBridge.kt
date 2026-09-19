package com.googleflow.mcp.engine

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

data class GenerationResult(
    val taskId: String,
    val mediaUrl: String,
    val metadataJson: String,
    val success: Boolean,
    val errorMessage: String? = null
)

data class NetworkTrafficItem(
    val id: String,
    val type: String,
    val timestamp: String,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, Any>?,
    val requestBody: Any?,
    val status: Int,
    val statusText: String?,
    val responseHeaders: Map<String, Any>?,
    val responseBody: String?,
    val durationMs: Long,
    val error: String? = null
)

data class ConsoleLogItem(
    val timestamp: String,
    val level: String,
    val message: String,
    val stack: String? = null
)

class FlowJsBridge {

    private val gson = Gson()
    private val maxHistorySize = 300

    private val _authState = MutableStateFlow(false)
    val authState = _authState.asStateFlow()

    private val _currentUrl = MutableStateFlow("https://labs.google/fx/")
    val currentUrl = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle = _pageTitle.asStateFlow()

    private val _generationEvents = MutableSharedFlow<GenerationResult>(extraBufferCapacity = 64)
    val generationEvents = _generationEvents.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    // Ring buffers for network traffic & console logs
    private val networkTrafficQueue = ConcurrentLinkedDeque<NetworkTrafficItem>()
    private val consoleLogsQueue = ConcurrentLinkedDeque<ConsoleLogItem>()

    @JavascriptInterface
    fun onAuthStatus(isLoggedIn: Boolean, url: String) {
        _authState.value = isLoggedIn
        _currentUrl.value = url
        _logs.tryEmit("Auth Status: loggedIn=$isLoggedIn, url=$url")
    }

    @JavascriptInterface
    fun onNetworkTraffic(jsonStr: String) {
        try {
            val item = gson.fromJson(jsonStr, NetworkTrafficItem::class.java)
            if (item != null) {
                networkTrafficQueue.addFirst(item)
                while (networkTrafficQueue.size > maxHistorySize) {
                    networkTrafficQueue.pollLast()
                }
                _logs.tryEmit("Traffic: ${item.method} ${item.url} -> ${item.status} (${item.durationMs}ms)")
            }
        } catch (e: Exception) {
            _logs.tryEmit("Traffic parse error: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onConsoleLog(level: String, message: String, stack: String) {
        try {
            val logItem = ConsoleLogItem(
                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()),
                level = level,
                message = message,
                stack = if (stack.isNotBlank()) stack else null
            )
            consoleLogsQueue.addFirst(logItem)
            while (consoleLogsQueue.size > maxHistorySize) {
                consoleLogsQueue.pollLast()
            }
            _logs.tryEmit("Console [$level]: $message")
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun onGenerationCompleted(taskId: String, mediaUrl: String, metadataJson: String) {
        _logs.tryEmit("Task $taskId completed: $mediaUrl")
        _generationEvents.tryEmit(
            GenerationResult(
                taskId = taskId,
                mediaUrl = mediaUrl,
                metadataJson = metadataJson,
                success = true
            )
        )
    }

    @JavascriptInterface
    fun onError(taskId: String, errorMessage: String) {
        _logs.tryEmit("Task $taskId error: $errorMessage")
        _generationEvents.tryEmit(
            GenerationResult(
                taskId = taskId,
                mediaUrl = "",
                metadataJson = "{}",
                success = false,
                errorMessage = errorMessage
            )
        )
    }

    @JavascriptInterface
    fun log(message: String) {
        _logs.tryEmit(message)
    }

    fun setPageInfo(url: String, title: String) {
        _currentUrl.value = url
        _pageTitle.value = title
    }

    fun getNetworkTraffic(filter: String? = null, limit: Int = 50): List<NetworkTrafficItem> {
        val list = networkTrafficQueue.toList()
        val filtered = if (filter.isNullOrBlank()) {
            list
        } else {
            val f = filter.lowercase()
            list.filter { it.url.lowercase().contains(f) || it.method.lowercase().contains(f) || (it.responseBody?.lowercase()?.contains(f) == true) }
        }
        return filtered.take(limit.coerceIn(1, 200))
    }

    fun clearNetworkTraffic() {
        networkTrafficQueue.clear()
    }

    fun getConsoleLogs(levelFilter: String? = null, limit: Int = 50): List<ConsoleLogItem> {
        val list = consoleLogsQueue.toList()
        val filtered = if (levelFilter.isNullOrBlank()) {
            list
        } else {
            val lf = levelFilter.lowercase()
            list.filter { it.level.lowercase() == lf }
        }
        return filtered.take(limit.coerceIn(1, 200))
    }

    fun clearConsoleLogs() {
        consoleLogsQueue.clear()
    }

    val trafficCount: Int get() = networkTrafficQueue.size
    val consoleCount: Int get() = consoleLogsQueue.size
}
