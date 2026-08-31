package com.googleflow.mcp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.googleflow.mcp.service.FlowOverlayService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    service: FlowOverlayService?,
    isOverlayPermissionGranted: Boolean,
    onOpenOverlayPermissionSettings: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<String>() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var testPrompt by remember { mutableStateOf("A cinematic retro cyberpunk street at night, neon lights, 8k") }
    var isGenerating by remember { mutableStateOf(false) }

    val authState by service?.engine?.bridge?.authState?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val currentUrl by service?.engine?.bridge?.currentUrl?.collectAsState(initial = "") ?: remember { mutableStateOf("") }

    LaunchedEffect(service) {
        service?.engine?.bridge?.logs?.collect { logMsg ->
            logs.add(0, "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $logMsg")
            if (logs.size > 200) logs.removeLast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Flow Logo",
                            tint = Color(0xFF4285F4)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Google Flow MCP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("127.0.0.1:8765 | ${if (authState) "Logged In ✓" else "Needs Login ⚠️"}", style = MaterialTheme.typography.bodySmall, color = if (authState) Color(0xFF34A853) else Color(0xFFFBBC05))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        service?.attachTo1x1Overlay()
                    }) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Send to 1x1 Background",
                            tint = Color(0xFF34A853)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1F20),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E1F20)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        service?.detachFromOverlay()
                        selectedTab = 0
                    },
                    icon = { Icon(Icons.Default.Language, contentDescription = "WebView") },
                    label = { Text("Google Flow") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Logs") },
                    label = { Text("MCP Logs") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF131314))
        ) {
            when (selectedTab) {
                0 -> {
                    // Fullscreen interactive WebView for Google Login
                    if (service != null) {
                        AndroidView(
                            factory = { ctx ->
                                (service.engine.webView ?: WebView(ctx).also {
                                    service.engine.attachWebView(it)
                                }).apply {
                                    (parent as? ViewGroup)?.removeView(this)
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF4285F4))
                        }
                    }
                }
                1 -> {
                    // Dashboard & Quick Controls
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        if (!isOverlayPermissionGranted) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF3C2F15)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFBBC05))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Overlay İzni Gerekli", fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("1x1 arka plan penceresi için izin verin.", fontSize = 12.sp, color = Color.LightGray)
                                    }
                                    Button(onClick = onOpenOverlayPermissionSettings) {
                                        Text("İzin Ver")
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F20)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("MCP Sunucu Durumu", fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("• Port: http://127.0.0.1:8765/sse", color = Color(0xFF8AB4F8), fontFamily = FontFamily.Monospace)
                                Text("• Oturum: ${if (authState) "Açık (Hazır)" else "Giriş Yapılmalı"}", color = if (authState) Color(0xFF34A853) else Color(0xFFEA4335))
                                Text("• URL: $currentUrl", maxLines = 1, fontSize = 12.sp, color = Color.Gray)
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { service?.attachTo1x1Overlay() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Minimize, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("1x1 Arka Plana Al")
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F20)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Hızlı Üretim Testi", fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = testPrompt,
                                    onValueChange = { testPrompt = it },
                                    label = { Text("Prompt") },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF4285F4)
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            if (service != null && !isGenerating) {
                                                isGenerating = true
                                                service.engine.generateImage(testPrompt) {
                                                    isGenerating = false
                                                }
                                            }
                                        },
                                        enabled = !isGenerating,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isGenerating) "Üretiliyor..." else "Görsel Üret")
                                    }
                                    Button(
                                        onClick = {
                                            if (service != null && !isGenerating) {
                                                isGenerating = true
                                                service.engine.generateVideo(testPrompt) {
                                                    isGenerating = false
                                                }
                                            }
                                        },
                                        enabled = !isGenerating,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Video Üret (Veo)")
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Live Terminal / Logs
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Text("MCP Canlı Günlük (Logs)", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0A0A0B), shape = RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    color = if (log.contains("error", true)) Color(0xFFEA4335) else Color(0xFF81C995),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
