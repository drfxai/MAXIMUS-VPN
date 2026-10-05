package com.example.ui.share

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.RayApplication
import com.example.data.model.VlessProfile
import com.example.ui.protocols.InterFamily
import com.example.ui.protocols.labColors
import com.example.vpn.VpnController
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.share.ShareCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private data class Received(val profile: VlessProfile, val isNew: Boolean)

/**
 * Reads servers shared from another phone ("Share offline"): MAXIMUS share codes, including a set of
 * codes shown one after another, and plain links from other apps' QR codes. Works with no internet.
 */
@Composable
fun ScanShareScreen(onBack: () -> Unit, onConnected: () -> Unit) {
    val c = labColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }

    val received = remember { mutableStateListOf<Received>() }
    val seen = remember { mutableSetOf<String>() }
    var total by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun onCode(text: String) {
        if (!seen.add(text)) return
        val part = ShareCode.decode(text)
        if (part != null && part.total > 1) total = part.total
        scope.launch {
            val profiles = withContext(Dispatchers.IO) { UniversalImportEngine.importText(text).validProfiles }
            if (profiles.isEmpty()) {
                message = "That code is not a server MAXIMUS can use."
                return@launch
            }
            val (inserted, _) = withContext(Dispatchers.IO) {
                RayApplication.instance.serverRepository.insertAllWithDeduplication(profiles)
            }
            val newIds = inserted.map { it.id }.toSet()
            profiles.forEach { p ->
                val saved = inserted.firstOrNull { it.effectiveFingerprint == p.effectiveFingerprint } ?: p
                received.add(0, Received(saved, saved.id in newIds))
            }
            message = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF15171C))) {
        if (granted) CameraPreview(onCode = { text -> onCode(text) })
        else Text(
            "Allow the camera to scan a code from the other phone.",
            color = Color.White, fontSize = 15.sp, fontFamily = InterFamily,
            modifier = Modifier.align(Alignment.Center).padding(32.dp).clickable { askCamera.launch(Manifest.permission.CAMERA) }
        )
        ScanFrame(Modifier.align(Alignment.TopCenter).padding(top = 140.dp).size(280.dp), c.accent)

        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Scan from another phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = InterFamily)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.card)
                .padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 22.dp).navigationBarsPadding()
        ) {
            val count = received.size
            val heading = when {
                count == 0 -> "Point the camera at the code"
                total != null -> "$count of $total servers received"
                count == 1 -> "1 server received"
                else -> "$count servers received"
            }
            Text(heading, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = InterFamily)
            total?.let { t ->
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.cardAlt)) {
                    Box(Modifier.fillMaxWidth(fraction = (count.toFloat() / t).coerceIn(0f, 1f)).height(4.dp).background(c.accent))
                }
            }
            LazyColumn(Modifier.heightIn(max = 220.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(received) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(14.dp)).background(c.cardAlt).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.profile.name, color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = InterFamily)
                            Text(if (r.isNew) "Added · ${kindLabel(r.profile)}" else "Already saved", color = c.text2, fontSize = 12.5.sp, fontFamily = InterFamily)
                        }
                        Text(
                            if (r.isNew) "New" else "Saved", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = InterFamily,
                            color = if (r.isNew) c.good else c.text2,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (r.isNew) c.good.copy(alpha = 0.14f) else c.cardAlt)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(c.cardAlt).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    message ?: "Works with no internet. Each code is checked before it is added; nothing is sent anywhere.",
                    color = if (message != null) c.bad else c.text2, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = InterFamily
                )
            }
            val first = received.firstOrNull { it.isNew }?.profile ?: received.firstOrNull()?.profile
            Box(
                Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(c.accent, c.accent2)))
                    .clickable {
                        if (first == null) onBack() else {
                            RayApplication.instance.settingsRepository.setSelectedProfileId(first.id)
                            if (VpnService.prepare(context) == null) VpnController.startVpn(RayApplication.instance, first)
                            onConnected()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (first == null) "Close" else "Done · connect",
                    color = c.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFamily = InterFamily
                )
            }
        }
    }
}

@Composable
private fun ScanFrame(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val len = size.minDimension * 0.22f
        val w = 6.dp.toPx()
        val right = size.width
        val bottom = size.height
        listOf(
            Offset(0f, len) to Offset(0f, 0f), Offset(0f, 0f) to Offset(len, 0f),
            Offset(right - len, 0f) to Offset(right, 0f), Offset(right, 0f) to Offset(right, len),
            Offset(0f, bottom - len) to Offset(0f, bottom), Offset(0f, bottom) to Offset(len, bottom),
            Offset(right - len, bottom) to Offset(right, bottom), Offset(right, bottom) to Offset(right, bottom - len)
        ).forEach { (from, to) -> drawLine(color, from, to, w, StrokeCap.Round) }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, QrAnalyzer { text -> ContextCompat.getMainExecutor(ctx).execute { onCode(text) } })
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )
}

/** Finds QR codes in the camera's luminance plane. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    private var last: String? = null

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            if (text != null && text != last) {
                last = text
                onText(text)
            }
        } finally {
            reader.reset()
            image.close()
        }
    }
}
