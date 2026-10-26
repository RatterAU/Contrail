package app.contrail.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Rotation matrix (device → world, row-major 3×3) plus whether the compass needs calibrating. */
class Orientation(val matrix: FloatArray, val needsCalibration: Boolean)

object DeviceSensors {
    /** Location updates from the platform (no Google Play services needed). Caller must hold permission. */
    @SuppressLint("MissingPermission")
    fun location(context: Context, intervalMs: Long = 5_000): Flow<Location> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { trySend(it) }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { trySend(it) }
        providers.forEach { p ->
            if (Build.VERSION.SDK_INT >= 31) {
                // Explicit high accuracy: the legacy overload may be served from Wi-Fi/cell only, too coarse for "overhead".
                val req = LocationRequest.Builder(intervalMs).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateDistanceMeters(10f).build()
                lm.requestLocationUpdates(p, req, context.mainExecutor, listener)
            } else {
                lm.requestLocationUpdates(p, intervalMs, 10f, listener, Looper.getMainLooper())
            }
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    /** Smoothed rotation-vector orientation at ~30 Hz. */
    fun orientation(context: Context): Flow<Orientation> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: run { close(); return@callbackFlow }
        val smooth = FloatArray(5)
        var primed = false
        var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = minOf(e.values.size, 4)
                // Low-pass the raw quaternion to calm hand shake; flip sign to stay on one hemisphere.
                if (primed) {
                    val dot = (0 until n).sumOf { (smooth[it] * e.values[it]).toDouble() }
                    val s = if (dot < 0) -1f else 1f
                    for (i in 0 until n) smooth[i] += (s * e.values[i] - smooth[i]) * 0.2f
                } else {
                    for (i in 0 until n) smooth[i] = e.values[i]
                    primed = true
                }
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, smooth.copyOf(n))
                trySend(Orientation(r, accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW))
            }

            override fun onAccuracyChanged(s: Sensor, a: Int) {
                accuracy = a
            }
        }
        sm.registerListener(listener, sensor, 33_000)
        awaitClose { sm.unregisterListener(listener) }
    }
}
