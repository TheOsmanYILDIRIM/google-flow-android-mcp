package com.googleflow.mcp.engine

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class GenerationResult(
    val taskId: String,
    val mediaUrl: String,
    val metadataJson: String,
    val success: Boolean,
    val errorMessage: String? = null
)

class FlowJsBridge {

    private val _authState = MutableStateFlow(false)
    val authState = _authState.asStateFlow()

    private val _currentUrl = MutableStateFlow("https://labs.google/fx/")
    val currentUrl = _currentUrl.asStateFlow()

    private val _generationEvents = MutableSharedFlow<GenerationResult>(extraBufferCapacity = 64)
    val generationEvents = _generationEvents.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    @JavascriptInterface
    fun onAuthStatus(isLoggedIn: Boolean, url: String) {
        _authState.value = isLoggedIn
        _currentUrl.value = url
        _logs.tryEmit("Auth Status: loggedIn=$isLoggedIn, url=$url")
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
}
