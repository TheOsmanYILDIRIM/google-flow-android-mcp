package com.googleflow.mcp.ui

import android.content.Intent
import android.net.Uri
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
import com.googleflow.mcp.engine.NetworkTrafficItem
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

    // Navigation bar state
    var urlInput by remember { mutableStateOf("https://labs.google/fx/tools/flow") }

    // Cookie import dialog state
    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }

    // Traffic expansion state
    var trafficList by remember { mutableStateOf<List<NetworkTrafficItem>>(emptyList()) }
    var expandedTrafficId by remember { mutableStateOf<String?>(null) }

    val authState by service?.engine?.bridge?.authState?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val currentUrl by service?.engine?.bridge?.currentUrl?.collectAsState(initial = "") ?: remember { mutableStateOf("") }
    val pageTitle by service?.engine?.bridge?.pageTitle?.collectAsState(initial = "") ?: remember { mutableStateOf("") }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank() && currentUrl != urlInput) {
            urlInput = currentUrl
        }
    }

    LaunchedEffect(service) {
        service?.engine?.bridge?.logs?.collect { logMsg ->
            logs.add(0, "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $logMsg")
            if (logs.size > 200) logs.removeLast()
        }
    }

    // Refresh traffic on tab switch
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2 && service != null) {
            trafficList = service.engine.bridge.getNetworkTraffic(null, 100)
        }
    }

    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Cookie / Oturum İçe Aktar", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Tarayıcı çerezlerini yapıştırarak oturumu anında aktarın:",
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
                            imageVector = Icons.Default.Language,
                            contentDescription = "Bridge Logo",
                            tint = Color(0xFF4285F4)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Android Browser Bridge v4.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (pageTitle.isNotBlank()) pageTitle else "127.0.0.1:8765 | Active ✓",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF81C995),
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        service?.engine?.captureScreenshot { _, _, _ -> }
                    }) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Ekran Görüntüsü",
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
                        service?.attachTo1x1Overlay()
                    }) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "1x1 Arka Plan",
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
                    icon = { Icon(Icons.Default.Web, contentDescription = "Browser") },
                    label = { Text("Tarayıcı") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Kontrol") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        if (service != null) {
                            trafficList = service.engine.bridge.getNetworkTraffic(null, 100)
                        }
                    },
                    icon = { Icon(Icons.Default.SwapVert, contentDescription = "Traffic") },
                    label = { Text("Ağ Trafiği (${service?.engine?.bridge?.trafficCount ?: 0})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Logs") },
                    label = { Text("Loglar") }
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
            // ALWAYS keep WebView active and attached in layout
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Universal Browser Navigation Bar
                    Surface(
                        color = Color(0xFF1E1F20),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { service?.engine?.goBack() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Geri", tint = Color.LightGray)
                                }
                                IconButton(
                                    onClick = { service?.engine?.goForward() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "İleri", tint = Color.LightGray)
                                }
                                IconButton(
                                    onClick = { service?.engine?.reload() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = Color.LightGray)
                                }

                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = Color.White),
                                    placeholder = { Text("URL girin (https://...)", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF4285F4),
                                        unfocusedBorderColor = Color(0xFF3C4043),
                                        focusedContainerColor = Color(0xFF2D2E30),
                                        unfocusedContainerColor = Color(0xFF2D2E30)
                                    )
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = {
                                        if (urlInput.isNotBlank()) {
                                            val target = if (!urlInput.startsWith("http://") && !urlInput.startsWith("https://")) "https://$urlInput" else urlInput
                                            service?.engine?.navigate(target)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Git", tint = Color(0xFF4285F4))
                                }
                            }

                            // Quick Links Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        service?.engine?.switchUserAgent(service.engine.firefoxUserAgent)
                                        service?.engine?.navigate("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Flabs.google%2Ffx%2Ftools%2Fflow")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("🔑 Google Giriş", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { service?.engine?.loadFlowUrl() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Flow", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.navigate("https://notebooklm.google.com/") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("NotebookLM", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.switchUserAgent(service.engine.firefoxUserAgent) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("🦊 Firefox", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.switchUserAgent(service.engine.safariUserAgent) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("🍎 Safari", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.switchUserAgent(service.engine.pixelChromeUserAgent) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("📱 Mobile Chrome", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        val target = if (urlInput.isNotBlank()) urlInput else "https://accounts.google.com/ServiceLogin"
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2E30)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("🌐 Chrome'da Aç", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { service?.engine?.getDom("interactive") {} },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C995)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("DOM Tree", color = Color.Black, fontSize = 11.sp)
                                }
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

            when (selectedTab) {
                0 -> { /* WebView visible */ }
                1 -> {
                    // Dashboard & Controls
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF131314))
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
                                Text("Universal Browser Bridge Durumu", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("• REST & MCP Port: http://127.0.0.1:8765", color = Color(0xFF8AB4F8), fontFamily = FontFamily.Monospace)
                                Text("• Yakalanan Ağ Trafiği: ${service?.engine?.bridge?.trafficCount ?: 0} istek", color = Color(0xFF81C995))
                                Text("• Konsol Logları: ${service?.engine?.bridge?.consoleCount ?: 0} satır", color = Color(0xFFFBBC05))
                                Text("• Aktif Sayfa: ${pageTitle.ifBlank { currentUrl }}", color = Color.LightGray, fontSize = 12.sp)

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { service?.attachTo1x1Overlay() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PictureInPicture, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("1x1 Arka Plan", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = { service?.engine?.clearAllCookies {} },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA4335)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Çerezleri Sıfırla", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // Network Traffic Sniffer View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF131314))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ağ Trafiği (${trafficList.size})", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                            Row {
                                TextButton(onClick = {
                                    service?.engine?.bridge?.clearNetworkTraffic()
                                    trafficList = emptyList()
                                }) {
                                    Text("Temizle", color = Color(0xFFEA4335), fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    trafficList = service?.engine?.bridge?.getNetworkTraffic(null, 100) ?: emptyList()
                                }) {
                                    Text("Yenile", color = Color(0xFF4285F4), fontSize = 12.sp)
                                }
                            }
                        }

                        if (trafficList.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Henüz yakalanan ağ isteği yok.\nWeb sitesinde gezinirken tüm fetch/XHR istekleri buraya akar.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(trafficList) { item ->
                                    val isExpanded = expandedTrafficId == item.id
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F20)),
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { expandedTrafficId = if (isExpanded) null else item.id }
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Surface(
                                                        color = if (item.method == "POST") Color(0xFF34A853) else Color(0xFF4285F4),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(item.method, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        color = if (item.status in 200..299) Color(0xFF1E3A2F) else if (item.status == 0) Color(0xFF3C1F1F) else Color(0xFF3C3015),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text("${item.status}", color = if (item.status in 200..299) Color(0xFF81C995) else Color(0xFFEA4335), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                                Text("${item.durationMs}ms", color = Color.Gray, fontSize = 11.sp)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(item.url, color = Color.White, fontSize = 12.sp, maxLines = if (isExpanded) 10 else 1, fontFamily = FontFamily.Monospace)

                                            if (isExpanded) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Divider(color = Color(0xFF2D2E30))
                                                Spacer(modifier = Modifier.height(8.dp))
                                                if (item.requestBody != null) {
                                                    Text("Request Body:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF8AB4F8))
                                                    Text(item.requestBody.toString(), fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                }
                                                if (!item.responseBody.isNullOrBlank()) {
                                                    Text("Response Body:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF81C995))
                                                    Text(item.responseBody, fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace, maxLines = 15)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // MCP & Console Logs
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF131314))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sistem & Konsol Logları", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                            TextButton(onClick = { logs.clear() }) {
                                Text("Temizle", color = Color(0xFFEA4335), fontSize = 12.sp)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { logMsg ->
                                Text(
                                    text = logMsg,
                                    color = if (logMsg.contains("error", ignoreCase = true)) Color(0xFFEA4335) else if (logMsg.contains("Traffic", ignoreCase = true)) Color(0xFF81C995) else Color.LightGray,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
