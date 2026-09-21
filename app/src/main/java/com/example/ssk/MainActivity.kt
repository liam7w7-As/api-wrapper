package com.example.ssk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private val homeUrl = "https://sistema.novaledbolivia.com/"

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimetype: String?,
        val contentLength: Long,
        val fileName: String
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var errorLayout: LinearLayout
    private lateinit var retryButton: Button

    private var lastSafeUrl: String = homeUrl
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var geolocationRequestOrigin: String? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingDownload: PendingDownload? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                // Si la pantalla de error está visible y vuelve la conexión,
                // reintentar automáticamente sin que el usuario toque el botón
                if (errorLayout.visibility == View.VISIBLE) {
                    retryLoading()
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            if (data == null || (data.data == null && data.clipData == null)) {
                // Foto capturada con la cámara
                cameraImageUri?.let { uri ->
                    results = arrayOf(uri)
                }
            } else {
                // Selección desde la galería / gestor de archivos
                results = data.data?.let { arrayOf(it) } ?: data.clipData?.let { clipData ->
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraImageUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, R.string.permission_camera_denied, Toast.LENGTH_LONG).show()
        }
        openFileChooser(pendingFileChooserParams, isGranted)
        pendingFileChooserParams = null
    }

    private val webMediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val request = pendingWebPermissionRequest
        pendingWebPermissionRequest = null

        if (request != null) {
            val grantedResources = mutableListOf<String>()
            if (permissions[Manifest.permission.CAMERA] == true) {
                grantedResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
                grantedResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }

            if (grantedResources.isNotEmpty()) {
                request.grant(grantedResources.toTypedArray())
            } else {
                request.deny()
                Toast.makeText(this, R.string.permission_media_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            geolocationCallback?.invoke(geolocationRequestOrigin, true, false)
        } else {
            geolocationCallback?.invoke(geolocationRequestOrigin, false, false)
            Toast.makeText(this, R.string.permission_location_denied, Toast.LENGTH_LONG).show()
        }
        geolocationCallback = null
        geolocationRequestOrigin = null
    }

    private val downloadPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        val download = pendingDownload
        pendingDownload = null

        if (isGranted && download != null) {
            startDownload(download)
        } else {
            Toast.makeText(this, R.string.permission_download_denied, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingLayout = findViewById(R.id.loadingLayout)
        errorLayout = findViewById(R.id.errorLayout)
        retryButton = findViewById(R.id.retryButton)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(true)
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        // Pull-to-refresh: solo se dispara cuando la página está arriba del todo
        // (si el usuario está scrolleado, el gesto lo consume la propia página)
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.purple_500)
        )
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingLayout.visibility = View.GONE
                swipeRefresh.isRefreshing = false

                // Detectar si la página cargada es JSON puro (respuesta de API)
                // Esto ocurre cuando el historial tiene URLs de endpoints que
                // no fueron interceptadas por isApiOrJsonUrl (ej: rutas SPA/Inertia).
                view?.evaluateJavascript(
                    """(function(){
                        try {
                            var t = (document.body ? document.body.innerText : '').trim();
                            return (t.startsWith('{') || t.startsWith('[')) ? 'json' : 'ok';
                        } catch(e) { return 'ok'; }
                    })()"""
                ) { result ->
                    if (result == "\"json\"") {
                        // Es una página JSON pura: volver a la ultima pagina HTML conocida.
                        runOnUiThread {
                            returnToSafePage(view)
                        }
                    } else {
                        errorLayout.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        url?.takeIf { !isApiOrJsonUrl(it) }?.let { lastSafeUrl = it }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Bloquear navegacion directa a endpoints de API/JSON o respuestas Inertia.
                if (request?.isForMainFrame == true && isJsonMainFrameRequest(request)) {
                    returnToSafePage(view)
                    return true
                }
                if (request?.isForMainFrame == true && isDocumentUrl(request.url?.toString().orEmpty())) {
                    val url = request.url.toString()
                    showDocumentOptions(
                        url = url,
                        userAgent = request.requestHeaders["User-Agent"] ?: webView.settings.userAgentString,
                        contentDisposition = null,
                        mimetype = guessMimeType(url),
                        contentLength = -1
                    )
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Si el WebView intenta renderizar JSON/Inertia como pagina, devolver
                // una pagina minima que regrese al ultimo HTML valido.
                if (request?.isForMainFrame == true && isJsonMainFrameRequest(request)) {
                    return blockedJsonResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showError()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                // Heredar configuraciones necesarias para mantener la sesión
                newWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    allowFileAccess = true
                    allowContentAccess = true
                }
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (request?.isForMainFrame == true && isJsonMainFrameRequest(request)) {
                            return true
                        }
                        if (request?.isForMainFrame == true) {
                            val url = request.url?.toString().orEmpty()
                            if (isDocumentUrl(url)) {
                                showDocumentOptions(
                                    url = url,
                                    userAgent = request.requestHeaders["User-Agent"] ?: webView.settings.userAgentString,
                                    contentDisposition = null,
                                    mimetype = guessMimeType(url),
                                    contentLength = -1
                                )
                            } else if (url.isNotBlank()) {
                                webView.loadUrl(url)
                            }
                            view?.destroy()
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val popupUrl = url.orEmpty()
                        if (popupUrl.isBlank() || popupUrl == "about:blank") return

                        if (isDocumentUrl(popupUrl)) {
                            showDocumentOptions(
                                url = popupUrl,
                                userAgent = webView.settings.userAgentString,
                                contentDisposition = null,
                                mimetype = guessMimeType(popupUrl),
                                contentLength = -1
                            )
                        } else {
                            webView.loadUrl(popupUrl)
                        }
                        view?.stopLoading()
                        view?.destroy()
                    }
                }
                newWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                    if (isDocumentUrl(url) || mimetype?.contains("pdf", ignoreCase = true) == true) {
                        showDocumentOptions(url, userAgent, contentDisposition, mimetype, contentLength)
                    } else {
                        handleDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                    }
                }
                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val hasCameraPerm = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCameraPerm) {
                    pendingFileChooserParams = fileChooserParams
                    showPermissionExplanation(
                        title = getString(R.string.permission_camera_title),
                        message = getString(R.string.permission_camera_message),
                        onAccepted = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onDeclined = {
                            openFileChooser(pendingFileChooserParams, false)
                            pendingFileChooserParams = null
                        }
                    )
                } else {
                    openFileChooser(fileChooserParams, true)
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val resources = request?.resources ?: return
                val permissionsToRequest = mutableListOf<String>()

                if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(Manifest.permission.CAMERA)
                    }
                }
                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                    }
                }

                if (permissionsToRequest.isNotEmpty()) {
                    pendingWebPermissionRequest = request
                    showPermissionExplanation(
                        title = getString(R.string.permission_media_title),
                        message = getString(R.string.permission_media_message),
                        onAccepted = {
                            webMediaPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                        },
                        onDeclined = {
                            pendingWebPermissionRequest?.deny()
                            pendingWebPermissionRequest = null
                        }
                    )
                } else {
                    showPermissionExplanation(
                        title = getString(R.string.permission_media_title),
                        message = getString(R.string.permission_media_message),
                        onAccepted = {
                            request.grant(resources)
                        },
                        onDeclined = {
                            request.deny()
                        }
                    )
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingWebPermissionRequest == request) {
                    pendingWebPermissionRequest = null
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )

                geolocationRequestOrigin = origin
                geolocationCallback = callback

                showPermissionExplanation(
                    title = getString(R.string.permission_location_title),
                    message = getString(R.string.permission_location_message),
                    onAccepted = {
                        val missingPermissions = permissions.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }

                        if (missingPermissions.isEmpty()) {
                            geolocationCallback?.invoke(geolocationRequestOrigin, true, false)
                            geolocationCallback = null
                            geolocationRequestOrigin = null
                        } else {
                            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
                        }
                    },
                    onDeclined = {
                        geolocationCallback?.invoke(geolocationRequestOrigin, false, false)
                        geolocationCallback = null
                        geolocationRequestOrigin = null
                    }
                )
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (isDocumentUrl(url) || mimetype?.contains("pdf", ignoreCase = true) == true) {
                showDocumentOptions(url, userAgent, contentDisposition, mimetype, contentLength)
            } else {
                handleDownload(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }

        retryButton.setOnClickListener {
            retryButton.isEnabled = false
            retryButton.text = getString(R.string.error_retrying)
            retryLoading()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    // Revisar el historial para saltar URLs de API/JSON
                    // que quedaron guardadas por llamadas AJAX de la web
                    val list = webView.copyBackForwardList()
                    val currentIndex = list.currentIndex
                    var targetIndex = currentIndex - 1

                    // Retroceder en el historial hasta encontrar una página real (no API)
                    while (targetIndex >= 0) {
                        val url = list.getItemAtIndex(targetIndex).url ?: ""
                        if (!isApiOrJsonUrl(url)) break
                        targetIndex--
                    }

                    if (targetIndex >= 0) {
                        // Saltar directamente a la página válida
                        val steps = currentIndex - targetIndex
                        webView.goBackOrForward(-steps)
                    } else {
                        // No hay página válida en el historial → cerrar
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl(homeUrl)
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    private fun showLoading() {
        errorLayout.visibility = View.GONE
        loadingLayout.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        swipeRefresh.isRefreshing = false
    }

    private fun showError() {
        webView.visibility = View.GONE
        loadingLayout.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        errorLayout.visibility = View.VISIBLE
        retryButton.isEnabled = true
        retryButton.text = getString(R.string.error_retry)
    }

    private fun retryLoading() {
        if (isNetworkAvailable()) {
            showLoading()
            webView.reload()
        } else {
            showError()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimetype))
        val download = PendingDownload(url, userAgent, contentDisposition, mimetype, contentLength, fileName)

        if (needsLegacyDownloadPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            showPermissionExplanation(
                title = getString(R.string.permission_download_title),
                message = getString(R.string.permission_download_message),
                onAccepted = {
                    downloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                onDeclined = {
                    pendingDownload = null
                }
            )
            return
        }

        startDownload(download)
    }

    private fun showDocumentOptions(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.document_options_title)
            .setMessage(R.string.document_options_message)
            .setPositiveButton(R.string.document_view) { _, _ ->
                openDocumentUrl(url, mimetype) {
                    handleDownload(url, userAgent, contentDisposition, mimetype, contentLength)
                }
            }
            .setNegativeButton(R.string.document_download) { _, _ ->
                handleDownload(url, userAgent, contentDisposition, mimetype, contentLength)
            }
            .setNeutralButton(R.string.permission_cancel, null)
            .show()
    }

    private fun openDocumentUrl(url: String, mimetype: String?, onFallbackToDownload: () -> Unit) {
        val uri = Uri.parse(url)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimetype ?: guessMimeType(url) ?: "*/*")
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(Intent.createChooser(viewIntent, getString(R.string.document_open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.document_no_viewer, Toast.LENGTH_LONG).show()
            onFallbackToDownload()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.document_open_failed, Toast.LENGTH_LONG).show()
            onFallbackToDownload()
        }
    }

    private fun startDownload(download: PendingDownload) {
        try {
            val dlRequest = DownloadManager.Request(Uri.parse(download.url))
            CookieManager.getInstance().getCookie(download.url)?.let { cookie ->
                dlRequest.addRequestHeader("Cookie", cookie)
            }
            dlRequest.addRequestHeader("User-Agent", download.userAgent ?: webView.settings.userAgentString)
            if (!download.mimetype.isNullOrBlank()) {
                dlRequest.setMimeType(download.mimetype)
            }
            dlRequest.setTitle(download.fileName)
            dlRequest.setDescription(getString(R.string.download_description))
            dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.fileName)
            dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(dlRequest)
            Toast.makeText(this, getString(R.string.download_started, download.fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun needsLegacyDownloadPermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }

    private fun sanitizeFileName(fileName: String): String {
        val safeName = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()

        return safeName.ifBlank { "archivo_descargado" }
    }

    private fun showPermissionExplanation(
        title: String,
        message: String,
        onAccepted: () -> Unit,
        onDeclined: () -> Unit = {}
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.permission_continue) { _, _ -> onAccepted() }
            .setNegativeButton(R.string.permission_cancel) { _, _ -> onDeclined() }
            .show()
    }

    private fun returnToSafePage(view: WebView?) {
        if (view?.canGoBack() == true) {
            view.goBack()
        } else {
            view?.loadUrl(lastSafeUrl.ifBlank { homeUrl })
        }
    }

    private fun blockedJsonResponse(): WebResourceResponse {
        val safeUrl = lastSafeUrl.ifBlank { homeUrl }
        val html = """
            <!doctype html>
            <html>
            <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body>
            <script>
                if (history.length > 1) {
                    history.back();
                } else {
                    location.replace(${escapeForJavaScriptString(safeUrl)});
                }
            </script>
            </body>
            </html>
        """.trimIndent()

        return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
    }

    private fun escapeForJavaScriptString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private fun isJsonMainFrameRequest(request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders.mapKeys { it.key.lowercase() }
        val accept = headers["accept"]?.lowercase().orEmpty()
        val xInertia = headers["x-inertia"]?.lowercase().orEmpty()
        val xRequestedWith = headers["x-requested-with"]?.lowercase().orEmpty()
        val url = request.url?.toString().orEmpty()

        return isApiOrJsonUrl(url) ||
                xInertia == "true" ||
                (accept.contains("application/json") && !accept.contains("text/html")) ||
                xRequestedWith == "xmlhttprequest"
    }

    /**
     * Detecta si una URL es un endpoint de API o devuelve JSON.
     * Estas URLs quedan en el historial del WebView por las llamadas AJAX
     * y al presionar atrás se renderizarían como texto JSON en pantalla.
     */
    private fun isApiOrJsonUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/api/") ||
               lower.contains("/json") ||
               lower.endsWith(".json") ||
               lower.contains("format=json") ||
               lower.contains("formato=json") ||
               lower.contains("/ajax/") ||
               lower.contains("?_=") ||       // patrón común de jQuery AJAX anti-cache
               lower.contains("callback=") || // JSONP
               lower.contains("inertia") ||   // Inertia.js (responde JSON a XMLHttpRequest)
               lower.contains("x-inertia") || // cabeceras Inertia
               lower.contains("/fetch") ||    // rutas de fetch genéricas
               lower.contains("/data")        // rutas de datos
    }

    private fun isDocumentUrl(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault(url.lowercase())
        val documentExtensions = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".csv", ".txt", ".rtf", ".odt", ".ods", ".odp"
        )

        return documentExtensions.any { path.endsWith(it) }
    }

    private fun guessMimeType(url: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    private fun openFileChooser(fileChooserParams: WebChromeClient.FileChooserParams?, hasCameraPermission: Boolean) {
        var takePictureIntent: Intent? = null

        if (hasCameraPermission) {
            takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            try {
                val photoFile = File.createTempFile("JPEG_${System.currentTimeMillis()}_", ".jpg", externalCacheDir ?: cacheDir)
                cameraImageUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            } catch (e: Exception) {
                takePictureIntent = null
                cameraImageUri = null
            }
        }

        val contentSelectionIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, "Seleccionar o tomar foto")
            if (takePictureIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
            }
        }

        try {
            filePickerLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            cameraImageUri = null
        }
    }
}
