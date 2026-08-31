package com.googleflow.mcp.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    service: FlowOverlayService?,
    isOverlayPermissionGranted: Boolean,
    onOpenOverlayPermissionSettings: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<String>() }

    // Cookie import dialog state
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }

    // Generation Controls
    var testPrompt by remember { mutableStateOf("A cinematic retro cyberpunk street, neon lights, 8k") }
    var selectedModel by remember { mutableStateOf("nano-banana-2") }
    var selectedRatio by remember { mutableStateOf("1:1") }
    var outputCount by remember { mutableIntStateOf(1) }
    var isGenerating by remember { mutableStateOf(false) }

    val authState by service?.engine?.bridge?.authState?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val currentUrl by service?.engine?.bridge?.currentUrl?.collectAsState(initial = "") ?: remember { mutableStateOf("") }

    val models = listOf("nano-banana-2" to "Nano Banana 2", "nano-banana" to "Nano Banana", "veo-3.1" to "Veo 3.1")
    val aspectRatios = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2")
    val counts = listOf(1, 2, 3, 4)

    LaunchedEffect(service) {
        service?.engine?.bridge?.logs?.collect { logMsg ->
            logs.add(0, "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $logMsg")
            if (logs.size > 200) logs.removeLast()
        }
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Cookie / Oturum İçe Aktar", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Tarayıcınızdan aldığınız 'labs.google' çerezlerini buraya yapıştırarak Google oturumunu anında aktarabilirsiniz:",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        placeholder = { Text("SID=...; HSID=...; SSID=...") },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cookieInput.isNotBlank()) {
                            service?.engine?.importCookies(cookieInput)
                            showCookieDialog = false
                            cookieInput = ""
                        }
                    }
                ) {
                    Text("Oturumu Yükle")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) {
                    Text("İptal")
                }
            }
        )
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
                            Text("Google Flow MCP v2.4", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("127.0.0.1:8765 | ${if (authState) "Ready ✓" else "Giriş Bekleniyor ⚠️"}", style = MaterialTheme.typography.bodySmall, color = if (authState) Color(0xFF34A853) else Color(0xFFFBBC05))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        service?.engine?.dumpDom {
                            // DOM dump saved
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "DOM Dump Al",
                            tint = Color(0xFF81C995)
                        )
                    }
                    IconButton(onClick = { showCookieDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Cookie Import",
                            tint = Color(0xFFFBBC05)
                        )
                    }
                    IconButton(onClick = {
                        service?.engine?.loadFlowUrl()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Flow'u Yenile",
                            tint = Color.White
                        )
                    }
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
                    Column(Modifier.fillMaxSize()) {
                        // Quick Action Bar on top of WebView
                        Surface(
                            color = Color(0xFF1E1F20),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { service?.engine?.loadFlowUrl() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Google Flow", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.loadImageFxUrl() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("ImageFX", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.loadVideoFxUrl() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("VideoFX", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.dumpDom {} },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C995)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Code, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("DOM Dump", color = Color.Black, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { showCookieDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBC05)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cookie Yapıştır", color = Color.Black, fontSize = 12.sp)
                                }
                            }
                        }

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
                }
                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
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
                                        Text("1x1 görünmez arka plan penceresi için izin verin.", fontSize = 12.sp, color = Color.LightGray)
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
                                Text("MCP Sunucu & İnceleme Araçları", fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("• Endpoint: http://127.0.0.1:8765/sse", color = Color(0xFF8AB4F8), fontFamily = FontFamily.Monospace)
                                Text("• Modeller: Nano Banana 2 & Veo 3.1", color = Color(0xFF81C995))
                                Text("• Oturum: ${if (authState) "Açık (Hazır ✓)" else "Giriş Bekleniyor ⚠️"}", color = if (authState) Color(0xFF34A853) else Color(0xFFEA4335))
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { service?.engine?.dumpDom {} },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C995)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("DOM Dökümü Al", color = Color.Black, fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = { service?.attachTo1x1Overlay() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Minimize, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("1x1 Arka Plan", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F20)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Parametrik Üretim Testi", fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Model:", fontSize = 12.sp, color = Color.Gray)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    models.forEach { (key, label) ->
                                        FilterChip(
                                            selected = selectedModel == key,
                                            onClick = { selectedModel = key },
                                            label = { Text(label) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("En/Boy Oranı (Aspect Ratio):", fontSize = 12.sp, color = Color.Gray)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    aspectRatios.forEach { ratio ->
                                        FilterChip(
                                            selected = selectedRatio == ratio,
                                            onClick = { selectedRatio = ratio },
                                            label = { Text(ratio) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Çoklu Çıktı Sayısı (Batch Count):", fontSize = 12.sp, color = Color.Gray)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    counts.forEach { c ->
                                        FilterChip(
                                            selected = outputCount == c,
                                            onClick = { outputCount = c },
                                            label = { Text("${c}x") }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

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

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        if (service != null && !isGenerating) {
                                            isGenerating = true
                                            if (selectedModel.contains("veo")) {
                                                service.engine.generateVideo(testPrompt, selectedModel, selectedRatio) {
                                                    isGenerating = false
                                                }
                                            } else {
                                                service.engine.generateImage(testPrompt, selectedModel, selectedRatio, outputCount) {
                                                    isGenerating = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isGenerating,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isGenerating) "Üretiliyor..." else "Test Başlat ($selectedModel, $selectedRatio, ${outputCount}x)")
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("MCP Canlı Günlük (Logs)", fontWeight = FontWeight.Bold, color = Color.White)
                            Button(
                                onClick = { service?.engine?.dumpDom {} },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C995)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("DOM Dump Al", color = Color.Black, fontSize = 11.sp)
                            }
                        }
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
