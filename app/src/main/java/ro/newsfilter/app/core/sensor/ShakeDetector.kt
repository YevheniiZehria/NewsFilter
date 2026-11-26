package ro.newsfilter.app.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt

/**
 * Detectează gestul de „scuturare” a telefonului folosind accelerometrul
 * (`Sensor.TYPE_ACCELEROMETER` prin [SensorManager]).
 *
 * Algoritm:
 * 1. Pentru fiecare eveniment calculăm forța g totală: `√(x² + y² + z²) / g`.
 *    În repaus valoarea este ≈ 1 (doar gravitația).
 * 2. O valoare peste [shakeThresholdG] este un „impuls”. Cerem [minImpulses]
 *    impulsuri într-o fereastră de [impulseWindowMs] ca să nu reacționăm la
 *    o simplă lovitură sau la așezarea bruscă a telefonului pe masă.
 * 3. Debouncing: după o scuturare detectată ignorăm evenimentele timp de
 *    [cooldownMs], astfel încât o scuturare lungă declanșează o singură reîmprospătare.
 *
 * Clasa nu cunoaște UI-ul: primește doar un callback. Înregistrarea la senzor
 * este controlată explicit prin [start] / [stop] (vezi [ShakeToRefreshEffect]
 * pentru legarea de ciclul de viață Compose).
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
    private val shakeThresholdG: Float = 2.2f,
    private val minImpulses: Int = 2,
    private val impulseWindowMs: Long = 600L,
    private val cooldownMs: Long = 2_000L
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var firstImpulseAt = 0L
    private var impulseCount = 0
    private var lastShakeAt = 0L
    private var isListening = false

    /** False pe dispozitivele fără accelerometru — UI-ul poate afișa un mesaj. */
    val isSupported: Boolean get() = accelerometer != null

    /** Pornește ascultarea senzorului (idempotent). */
    fun start() {
        if (isListening || accelerometer == null) return
        isListening = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        ) == true
    }

    /** Oprește ascultarea senzorului — economisește bateria când ecranul nu e vizibil. */
    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        impulseCount = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < shakeThresholdG) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastShakeAt < cooldownMs) return

        if (impulseCount == 0 || now - firstImpulseAt > impulseWindowMs) {
            firstImpulseAt = now
            impulseCount = 1
        } else {
            impulseCount++
        }

        if (impulseCount >= minImpulses) {
            impulseCount = 0
            lastShakeAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Verifică rapid dacă dispozitivul are accelerometru. */
        fun isAvailable(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }
}

/**
 * Leagă [ShakeDetector] de ciclul de viață al ecranului Compose:
 * senzorul ascultă doar cât timp ecranul este **RESUMED** și [enabled] = true,
 * iar la ieșirea din compoziție (`onDispose`) listener-ul este dezînregistrat.
 */
@Composable
fun ShakeToRefreshEffect(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnShake by rememberUpdatedState(onShake)
    val detector = remember(context) { ShakeDetector(context, onShake = { currentOnShake() }) }

    DisposableEffect(lifecycleOwner, enabled, detector) {
        if (!enabled) {
            detector.stop()
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> detector.start()
                Lifecycle.Event.ON_PAUSE -> detector.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            detector.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            detector.stop()
        }
    }
}
