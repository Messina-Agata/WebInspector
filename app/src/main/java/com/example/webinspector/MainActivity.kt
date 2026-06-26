package com.example.webinspector

import android.annotation.SuppressLint
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.border
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavigationApp()
                }
            }
        }
    }
}


data class NetworkRequest(
    val url: String,
    val method: String,
    val headers: String,
    val payload: String,
    val response: String
)

private val privacyPolicyLink = "https://github.com/Messina-Agata/WebInspector/blob/main/PrivacyPolicy.md"
private val termsOfServiceLink = "https://github.com/Messina-Agata/WebInspector/blob/main/TermsOfService.md"
private val attributionLink = "https://github.com/Messina-Agata/WebInspector/blob/main/Attribution.md"
private val licenseLink = "https://github.com/Messina-Agata/WebInspector/blob/main/LICENSE.md"
private val aboutLink = "https://github.com/Messina-Agata/WebInspector/blob/main/README.md"

class InspectorViewModel : ViewModel() {
    val inputUrl = MutableStateFlow("")
    val htmlContent = MutableStateFlow("")
    val consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val networkRequests = MutableStateFlow<List<NetworkRequest>>(emptyList())
    val selectedRequest = MutableStateFlow<NetworkRequest?>(null)

    fun clearOldData() {
        htmlContent.value = "Loading HTML..."
        consoleLogs.value = emptyList()
        networkRequests.value = emptyList()
        selectedRequest.value = null
    }

    fun addOrMergeRequest(newLog: NetworkRequest) {
        val currentList = networkRequests.value.toMutableList()
        currentList.add(newLog)
        networkRequests.value = currentList
    }

    fun updateRequestWithJsData(url: String, method: String, payload: String, response: String) {
        val currentList = networkRequests.value.toMutableList()
        val cleanJsUrl = url.trim().removeSuffix("/")

        val index = currentList.indexOfLast {
            it.url.trim().removeSuffix("/") == cleanJsUrl &&
                    it.method.equals(method, ignoreCase = true) &&
                    (it.response == "[]" || it.payload == "N/A")
        }

        if (index != -1) {
            currentList[index] = currentList[index].copy(
                payload = if (payload.isNotBlank() && payload != "N/A") payload else currentList[index].payload,
                response = if (response.isNotBlank()) response else currentList[index].response,
            )
        } else {
            val newLog = NetworkRequest(
                url = url,
                method = method,
                headers = "N/A",
                payload = payload,
                response = response
            )
            currentList.add(newLog)
        }

        networkRequests.value = currentList
    }
}

class WebInspectorBridge(
    private val onHtml: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onNetworkData: (String, String, String, String) -> Unit
) {
    @JavascriptInterface
    fun sendHtml(html: String) { onHtml(html) }

    @JavascriptInterface
    fun sendConsoleLog(msg: String) { onLog(msg) }

    @JavascriptInterface
    fun logNetworkRequest(url: String, method: String, payload: String, response: String) {
        onNetworkData(url, method, payload, response)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BackgroundWebView(url: String, viewModel: InspectorViewModel) {
    if (url.isBlank() || !URLUtil.isValidUrl(url)) return

    var currentLoadedUrl by remember { mutableStateOf("") }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                clearCache(true)
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.textZoom = 100
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                settings.mediaPlaybackRequiresUserGesture = false

                val bridge = WebInspectorBridge(
                    onHtml = { html -> viewModel.htmlContent.value = html },
                    onLog = { log ->
                        if (log.isNotBlank() && !viewModel.consoleLogs.value.contains(log)) {
                            viewModel.consoleLogs.value += log
                        }
                    },
                    onNetworkData = { reqUrl, method, payload, response ->
                        post {
                            viewModel.updateRequestWithJsData(reqUrl, method, payload, response)
                        }
                    }
                )
                addJavascriptInterface(bridge, "AndroidBridge")

                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        if (consoleMessage != null) {
                            val level = consoleMessage.messageLevel().name
                            val msg = "[${level.uppercase()}] ${consoleMessage.message()}"
                            if (!viewModel.consoleLogs.value.contains(msg)) {
                                viewModel.consoleLogs.value += msg
                            }
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    val networkPayloadSpy = """
                        (function() {
                            // To prevent double injection
                            if (window.__networkInterceptorInitialized) return;
                            window.__networkInterceptorInitialized = true;
                        
                            function sendToAndroid(url, method, payload, response) {
                                if (window.AndroidBridge) {
                                    window.AndroidBridge.logNetworkRequest(url, method, payload, response);
                                }
                            }
                        
                            // Fetch
                            const originalFetch = window.fetch;
                            window.fetch = async function(...args) {
                                const url = args[0] instanceof Request ? args[0].url : args[0];
                                const options = args[1] || {};
                                const method = options.method || (args[0] instanceof Request ? args[0].method : 'GET');
                                
                                let payload = 'N/A';
                                if (options.body) {
                                    payload = options.body;
                                } else if (args[0] instanceof Request && args[0].body) {
                                    try { payload = await args[0].clone().text(); } catch(e) {}
                                }
                        
                                try {
                                    const response = await originalFetch(...args);
                                    const clone = response.clone();
                                    const responseText = await clone.text();
                                    
                                    sendToAndroid(url, method, String(payload), responseText);
                                    return response;
                                } catch (error) {
                                    sendToAndroid(url, method, String(payload), "Error: " + error.message);
                                    throw error;
                                }
                            };
                        
                            // XHR
                            const open = XMLHttpRequest.prototype.open;
                            const send = XMLHttpRequest.prototype.send;
                        
                            XMLHttpRequest.prototype.open = function(method, url) {
                                this._url = url;
                                this._method = method;
                                return open.apply(this, arguments);
                            };
                        
                            XMLHttpRequest.prototype.send = function(body) {
                                this._payload = body || 'N/A';
                                
                                this.addEventListener('load', function() {
                                    let responseText = '';
                                    if (this.responseType === '' || this.responseType === 'text') {
                                        responseText = this.responseText;
                                    } else {
                                        responseText = `[Blob/ArrayBuffer: ${'$'}{this.responseType}]`;
                                    }
                                    sendToAndroid(this._url, this._method, String(this._payload), responseText);
                                });
                        
                                this.addEventListener('error', function() {
                                    sendToAndroid(this._url, this._method, String(this._payload), "XHR Error");
                                });
                        
                                return send.apply(this, arguments);
                            };
                        })();
                    """.trimIndent()

                    override fun onLoadResource(view: WebView?, urlResource: String?) {
                        super.onLoadResource(view, urlResource)
                        view?.evaluateJavascript(networkPayloadSpy, null)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript("""
                            (function() {
                                if (!window.isConsoleSpied) {
                                    window.isConsoleSpied = true;
                                    const types = ['log', 'error', 'warn', 'info', 'debug'];
                                    types.forEach(type => {
                                        const original = console[type];
                                        console[type] = function(...args) {
                                            original.apply(console, args);
                                            try {
                                                window.AndroidBridge.sendConsoleLog("[" + type.toUpperCase() + "] " + args.join(' '));
                                            } catch(e) {}
                                        };
                                    });
                                }
                            })();
                        """.trimIndent(), null)
                        view?.evaluateJavascript(networkPayloadSpy, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(networkPayloadSpy, null)
                        view?.evaluateJavascript("document.documentElement.outerHTML;") { rawHtml ->
                            if (rawHtml != null && rawHtml != "null") {
                                var cleanHtml = rawHtml
                                if (cleanHtml.startsWith("\"") && cleanHtml.endsWith("\"") && cleanHtml.length > 2) {
                                    cleanHtml = cleanHtml.substring(1, cleanHtml.length - 1)
                                }
                                cleanHtml = cleanHtml
                                    .replace("\\u003C", "<")
                                    .replace("\\u003E", ">")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                    .replace("\\n", "\n")
                                    .replace("\\r", "\r")

                                viewModel.htmlContent.value = cleanHtml
                            }
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val method = request.method
                        val browserHeaders = request.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        val fullHeadersStringBuilder = StringBuilder().apply {
                            append("Request Method: $method\n")
                            append(browserHeaders)
                        }

                        val networkLog = NetworkRequest(
                            url = reqUrl,
                            method = method,
                            headers = fullHeadersStringBuilder.toString(),
                            payload = request.url.query ?: "N/A",
                            response = "[]"
                        )

                        view.post {
                            viewModel.addOrMergeRequest(networkLog)
                        }
                        return null
                    }
                }
            }
        },
        update = { webView ->
            if (url != currentLoadedUrl) {
                currentLoadedUrl = url
                viewModel.clearOldData()
                webView.clearCache(true)
                webView.loadUrl(url)
            }
        },
        modifier = Modifier.size(0.dp)
    )
}

@Composable
fun NavigationApp() {
    val navController = rememberNavController()
    val viewModel: InspectorViewModel = viewModel()

    BackgroundWebView(url = viewModel.inputUrl.collectAsState().value, viewModel = viewModel)

    NavHost(navController = navController, startDestination = "main") {
        composable("main") { MainScreen(navController, viewModel) }
        composable("elements") { ElementsScreen(navController, viewModel) }
        composable("console") { ConsoleScreen(navController, viewModel) }
        composable("network") { NetworkScreen(navController, viewModel) }
        composable("network_detail") { NetworkDetailScreen(navController, viewModel) }
        composable("headers") { RequestDataScreen(navController, "Headers", viewModel.selectedRequest.value?.headers ?: "") }
        composable("payload") { RequestDataScreen(navController, "Payload", viewModel.selectedRequest.value?.payload ?: "") }
        composable("response") { RequestDataScreen(navController, "Response", viewModel.selectedRequest.value?.response ?: "", showSearch = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: InspectorViewModel) {
    val url by viewModel.inputUrl.collectAsState()
    val context = LocalContext.current

    var showInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotBlank()) {
            kotlinx.coroutines.delay(1000)
            if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
                !trimmedUrl.startsWith("https://", ignoreCase = true) &&
                URLUtil.isValidUrl("https://$trimmedUrl")
            ) {
                viewModel.inputUrl.value = "https://$trimmedUrl"
            }
        }
    }

    fun checkAndNavigate(route: String) {
        // Remove white spaces
        var finalUrl = url.trim()

        if (finalUrl.isNotBlank()) {
            if (!finalUrl.startsWith("http://", ignoreCase = true) &&
                !finalUrl.startsWith("https://", ignoreCase = true)) {
                finalUrl = "https://$finalUrl"
            }

            viewModel.inputUrl.value = finalUrl
        }

        if (finalUrl.isBlank() || !URLUtil.isValidUrl(finalUrl)) {
            Toast.makeText(context, "URL not valid", Toast.LENGTH_SHORT).show()
        } else {
            navController.navigate(route)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .background(Color.White, shape = RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFE0E0E0), shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "WebInspector", fontSize = 32.sp, color = Color(0xFF2E7D32), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { viewModel.inputUrl.value = it },
            placeholder = { Text("Enter the URL to inspect...") },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, shape = OutlinedTextFieldDefaults.shape),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = Color(0xFF2E7D32), unfocusedBorderColor = Color(0xFFCCCCCC))
        )
        Spacer(modifier = Modifier.height(16.dp))
        InspectorButton("Elements analysis") { checkAndNavigate("elements") }
        InspectorButton("Console analysis") { checkAndNavigate("console") }
        InspectorButton("Network analysis") { checkAndNavigate("network") }

        Spacer(modifier = Modifier.height(50.dp))

        // Info button
        IconButton(
            onClick = { showInfoDialog = true },
            modifier = Modifier
                .size(35.dp)
                .background(Color.White, shape = androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, Color.Gray, shape = androidx.compose.foundation.shape.CircleShape)
        ) {
            Text(
                text = "i",
                color = Color.Gray,
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
    if (showInfoDialog) {
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showInfoDialog = false }, // close the panel when clicking outside
            title = {
                Text(
                    text = "Info",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val privacyPolicy = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = privacyPolicyLink)
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("Privacy Policy")
                        }
                        pop()
                    }
                    androidx.compose.foundation.text.ClickableText(
                        text = privacyPolicy,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            privacyPolicy.getStringAnnotations(
                                tag = "URL",
                                start = offset,
                                end = offset
                            )
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )

                    val termsOfService = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = termsOfServiceLink)
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("Terms of Service")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = termsOfService,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            termsOfService.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )

                    val attribution = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = attributionLink)
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("Attribution")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = attribution,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            attribution.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )

                    val license = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = licenseLink)
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("License")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = license,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            license.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )

                    val about = buildAnnotatedString {
                        pushStringAnnotation(tag = "URL", annotation = aboutLink)
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("About")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = about,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            about.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )
                    Text("Version: v1.0.1")
                    val contact = buildAnnotatedString {
                        append("For requests or issues, please open an issue on the ")
                        pushStringAnnotation(tag = "URL", annotation = "https://github.com/Messina-Agata/WebInspector/issues")
                        withStyle(style = SpanStyle(color = Color(0xFF0066CC), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) {
                            append("official repo")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = contact,
                        style = LocalTextStyle.current.copy(fontSize = 14.sp),
                        onClick = { offset ->
                            contact.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    uriHandler.openUri(annotation.item)
                                }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close", color = Color(0xFF2E7D32), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun InspectorButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(55.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1EA84B))
    ) {
        Text(text = text, fontSize = 18.sp, color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementsScreen(navController: NavController, viewModel: InspectorViewModel) {
    val html by viewModel.htmlContent.collectAsState()
    var search by remember { mutableStateOf("") }
    val context = LocalContext.current

    var selectedTextFromJs by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<android.webkit.WebView?>(null) }

    val isLoading = html == "Loading HTML..." || html.isBlank()

    LaunchedEffect(html) {
        if (!isLoading) {
            webViewInstance?.let { webView ->
                val formattedHtml = html.replace("><", ">\n<")
                val safeTextHtml = formattedHtml
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;")
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$")
                    .replace("\n", "\\n")

                val webPage = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { font-family: monospace; font-size: 13px; margin: 10px; background-color: #ffffff; color: #333333; }
                        .line { padding: 4px; word-wrap: break-word; white-space: pre-wrap; border-radius: 4px; }
                        .line:active { background-color: #e0f2f1; }
                        .selected-line { background-color: #b2dfdb !important; }
                    </style>
                    </head>
                    <body>
                        <div id="container"></div>
                        <script>
                            const rawData = `html_placeholder`;
                            const lines = rawData.split('\\n');
                            const container = document.getElementById('container');
                            lines.forEach((lineText) => {
                                if (lineText.trim() === '') return;
                                const div = document.createElement('div');
                                div.className = 'line';
                                const txtNode = document.createElement('textarea');
                                txtNode.innerHTML = lineText;
                                div.textContent = txtNode.value;
                                div.onclick = function() {
                                    document.querySelectorAll('.line').forEach(el => el.classList.remove('selected-line'));
                                    div.classList.add('selected-line');
                                    AndroidSelectionBridge.onTextSelected(div.textContent);
                                };
                                container.appendChild(div);
                            });
                        </script>
                    </body>
                    </html>
                """.trimIndent().replace("html_placeholder", safeTextHtml)

                webView.loadDataWithBaseURL(null, webPage, "text/html", "utf-8", null)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Elements") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTextFromJs.isNotBlank()) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("HTML Inspector", selectedTextFromJs))
                                Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show()
                                selectedTextFromJs = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1EA84B)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Copy line", color = Color.White)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp)
        ) {
            if (!isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { newValue ->
                            search = newValue
                            if (newValue.isNotBlank()) {
                                webViewInstance?.findAllAsync(newValue)
                            } else {
                                webViewInstance?.clearMatches()
                            }
                        },
                        placeholder = { Text("Search in HTML...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(
                        onClick = { webViewInstance?.findNext(false) },
                        enabled = search.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("▲", color = Color.White)
                    }

                    Button(
                        onClick = { webViewInstance?.findNext(true) },
                        enabled = search.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("▼", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading HTML...",
                            fontSize = 16.sp,
                            color = Color.Gray,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        )
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            setBackgroundColor(0)

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.textZoom = 100

                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onTextSelected(text: String) {
                                    selectedTextFromJs = text
                                }
                            }, "AndroidSelectionBridge")

                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (search.isNotBlank()) {
                                        findAllAsync(search)
                                    }
                                }
                            }
                            webViewInstance = this
                        }
                    },
                    update = { webView ->
                        webView.visibility = if (isLoading) android.view.View.INVISIBLE else
                            android.view.View.VISIBLE
                             },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    navController: NavController,
    viewModel: InspectorViewModel,
) {
    val logs by viewModel.consoleLogs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Console") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            items(logs) { log ->
                var expanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { expanded = !expanded },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (expanded) log else "${log.take(50)}...",
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(
    navController: NavController,
    viewModel: InspectorViewModel,
) {
    val requests by viewModel.networkRequests.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val filteredRequests = requests.filter {
        it.url.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter requests...") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredRequests) { req ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFFF0F0F0), RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    viewModel.selectedRequest.value = req
                                    navController.navigate("network_detail")
                                },
                                onLongClick = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", req.url))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT)
                                        .show()
                                },
                            )
                            .padding(16.dp),
                    ) {
                        Text(
                            text = req.url,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    navController: NavController,
    viewModel: InspectorViewModel,
) {
    val req = viewModel.selectedRequest.collectAsState().value ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Request details") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = req.url,
                onValueChange = {},
                readOnly = true,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )

            Spacer(modifier = Modifier.height(16.dp))

            InspectorButton("Headers") {
                navController.navigate("headers")
            }
            InspectorButton("Payload") {
                navController.navigate("payload")
            }
            InspectorButton("Response") {
                navController.navigate("response")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDataScreen(
    navController: NavController,
    title: String,
    content: String,
    showSearch: Boolean = false,
) {
    var search by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
        ) {
            if (showSearch) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            SelectionContainer {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = highlightText(content, search),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}


fun highlightText(
    fullText: String,
    query: String,
): androidx.compose.ui.text.AnnotatedString {
    if (query.isBlank()) {
        return buildAnnotatedString { append(fullText) }
    }

    return buildAnnotatedString {
        var startIdx = 0
        while (startIdx < fullText.length) {
            val index = fullText.indexOf(query, startIdx, ignoreCase = true)
            if (index == -1) {
                append(fullText.substring(startIdx))
                break
            }

            append(fullText.substring(startIdx, index))
            withStyle(style = SpanStyle(background = Color.Yellow)) {
                append(fullText.substring(index, index + query.length))
            }
            startIdx = index + query.length
        }
    }
}
