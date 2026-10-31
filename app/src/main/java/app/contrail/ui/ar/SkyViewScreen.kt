package app.contrail.ui.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import kotlinx.coroutines.awaitCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.contrail.data.device.Orientation
import app.contrail.domain.Fmt
import app.contrail.domain.Geo
import app.contrail.domain.SkyGeometry
import app.contrail.ui.nearby.NearbyItem
import app.contrail.ui.theme.Numeric
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Camera preview with live labels on the aircraft you're pointing at. Positions come from the
 * phone's location + rotation-vector sensor, so accuracy depends on compass calibration.
 */
@Composable
fun SkyViewScreen(items: List<NearbyItem>?, orientation: () -> Orientation?, onAircraft: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraPreview()

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val hFov = remember(wPx, hPx) { visibleHorizontalFov(context, wPx / hPx) }
            // Labels are placed in the layout phase from the live orientation, so the 30 Hz sensor
            // moves them without recomposing. Far ones first so near labels sit on top.
            val shown = remember(items) { items.orEmpty().filter { it.sky.elevation >= -2 }.sortedByDescending { it.sky.slantNm } }
            shown.forEach { item ->
                SkyLabel(
                    item,
                    Modifier.offset {
                        val r = orientation()?.matrix
                        val p = r?.let { SkyGeometry.project(it, item.sky.bearing, item.sky.elevation, hFov, (wPx / hPx).toDouble()) }
                        if (p == null || p.first !in -0.1..1.1 || p.second !in -0.1..1.1) OFF_SCREEN
                        else IntOffset((p.first * wPx).roundToInt() - 60, (p.second * hPx).roundToInt() - 20)
                    },
                    onClick = { onAircraft(item.track.hex) },
                )
            }
        }

        Column(Modifier.statusBarsPadding().padding(12.dp)) {
            Surface(onClick = onClose, shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp), tint = Color.White) }
            }
        }
        FacingChip(orientation, Modifier.align(Alignment.TopCenter))
        CalibrationHint(orientation, Modifier.align(Alignment.BottomCenter))
        if (!granted) {
            Text(
                "Camera access is needed for sky view. Labels still follow your phone without it.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
    }
}

private val OFF_SCREEN = IntOffset(-100_000, -100_000)

@Composable
private fun FacingChip(orientation: () -> Orientation?, modifier: Modifier) {
    val pointing = orientation()?.matrix?.let { SkyGeometry.cameraPointing(it) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = modifier.statusBarsPadding().padding(top = 16.dp),
    ) {
        Text(
            pointing?.let { (h, e) -> "Facing ${Geo.compass(h)} ${h.roundToInt() % 360}° · ${e.roundToInt()}° up" } ?: "Starting sensors…",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.merge(Numeric),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CalibrationHint(orientation: () -> Orientation?, modifier: Modifier) {
    if (orientation()?.needsCalibration != true) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.padding(24.dp),
    ) {
        Text(
            "Compass needs calibrating: wave your phone in a figure-8 a few times.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SkyLabel(item: NearbyItem, modifier: Modifier, onClick: () -> Unit) {
    val a = item.track.aircraft
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(a.label, color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(
                "${a.typeCode ?: ""} ${Fmt.altitude(a)} · ${Fmt.nm(item.sky.slantNm)}".trim(),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall.merge(Numeric),
            )
        }
    }
}

@Composable
private fun CameraPreview() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    LaunchedEffect(owner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        provider.unbindAll()
        runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview) }
        // The owner is the activity, which outlives this page: let go of the camera when the page closes.
        try { awaitCancellation() } finally { provider.unbindAll() }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}

/**
 * Horizontal field of view actually visible on screen in portrait: the sensor's short side spans
 * the screen width, and FILL_CENTER crops the sides to fit the tall screen.
 */
private fun visibleHorizontalFov(context: Context, screenAspect: Float): Double {
    val fallback = 50.0
    return runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val ch = cm.getCameraCharacteristics(id)
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first().toDouble()
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        val shortSide = minOf(size.width, size.height).toDouble()
        val longSide = maxOf(size.width, size.height).toDouble()
        val imageAspect = shortSide / longSide // portrait image width / height
        val visibleFraction = (screenAspect / imageAspect).coerceAtMost(1.0)
        Math.toDegrees(2 * atan(tan(atan(shortSide / (2 * f))) * visibleFraction))
    }.getOrDefault(fallback)
}
