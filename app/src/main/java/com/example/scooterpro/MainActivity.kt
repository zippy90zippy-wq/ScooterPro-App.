package com.example.scooterpro

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.hardware.*
import android.location.Location
import android.os.*
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.util.*

// --- 1. RDZEŃ PROTOKOŁU (XIAOMI PRO 4 GEN 2) ---
object ScooterCore {
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    fun buildPacket(reg: Int, data: ByteArray): ByteArray {
        val payload = byteArrayOf((data.size + 2).toByte(), 0x03.toByte(), reg.toByte()) + data
        var cs = 0
        for (b in payload) cs += b.toInt() and 0xFF
        cs = (cs xor 0xFFFF) and 0xFFFF
        return byteArrayOf(0x55.toByte(), 0xAA.toByte()) + payload + byteArrayOf((cs and 0xFF).toByte(), (cs shr 8 and 0xFF).toByte())
    }
}

// --- 2. SILNIK APLIKACJI (LOGIKA & SENSORY) ---
class AppEngine(val ctx: Context) : SensorEventListener {
    var speed = mutableStateOf(0)
    var battery = mutableStateOf(0)
    var isHudMode = mutableStateOf(false)
    var isLocked = mutableStateOf(false)
    var voltage = mutableStateOf(48.2)
    var isConnected = mutableStateOf(false)
    var logList = mutableStateListOf<String>()

    private var tts: TextToSpeech? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    init {
        tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_UI)
    }

    fun speak(msg: String) = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)

    override fun onSensorChanged(event: SensorEvent?) {
        // Detekcja wypadku (G > 25)
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val g = Math.sqrt((event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]).toDouble())
            if (g > 25) { 
                speak("Wykryto upadek. Wysyłam powiadomienie SOS.")
                // Tu funkcja wysyłania SMS
            }
        }
        // Inteligentne światła
        if (event?.sensor?.type == Sensor.TYPE_LIGHT && event.values[0] < 5) {
            if (isConnected.value) sendCommand(0x1A, 0x01) 
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    @SuppressLint("MissingPermission")
    fun sendCommand(reg: Int, value: Byte) {
        val p = ScooterCore.buildPacket(reg, byteArrayOf(value))
        writeChar?.let { it.value = p; gatt?.writeCharacteristic(it) }
        logList.add("CMD: Reg $reg Val $value")
    }
}

// --- 3. INTERFEJS UŻYTKOWNIKA (JETPACK COMPOSE) ---
@Composable
fun ScooterProUI(engine: AppEngine) {
    val scrollState = rememberScrollState()
    
    Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Column(Modifier.verticalScroll(scrollState).scale(if(engine.isHudMode.value) -1f else 1f)) {
            
            // Header: Bateria i Zegar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BATERIA: ${engine.battery.value}%", color = Color.Green, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("HH:mm").format(Date()), color = Color.White)
            }

            Spacer(Modifier.height(40.dp))

            // Licznik
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${engine.speed.value}", fontSize = 120.sp, color = Color.White, fontWeight = FontWeight.Black)
                Text("KM/H", color = Color.Cyan, modifier = Modifier.offset(y = (-20).dp))
            }

            // Diagnostyka
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                InfoTile("Napięcie", "${engine.voltage.value}V")
                InfoTile("Zasięg", "~${(engine.battery.value * 0.5).toInt()}km")
            }

            Spacer(Modifier.height(30.dp))

            // Centrum Sterowania
            Text("KONTROLA SYSTEMU", color = Color.Gray, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionBtn(Icons.Default.Lock, engine.isLocked.value) { engine.sendCommand(0x17, 0x01) }
                ActionBtn(Icons.Default.Speed, false) { engine.sendCommand(0x70, 0x01) }
                ActionBtn(Icons.Default.Visibility, engine.isHudMode.value) { engine.isHudMode.value = !engine.isHudMode.value }
                ActionBtn(Icons.Default.Sos, false) { engine.speak("System SOS gotowy") }
            }

            Spacer(Modifier.height(30.dp))

            // Czarna Skrzynka
            Text("LOGI SYSTEMOWE", color = Color.Gray, fontSize = 12.sp)
            Card(Modifier.fillMaxWidth().height(100.dp).padding(top = 8.dp), backgroundColor = Color(0xFF111111)) {
                LazyColumn {
                    items(engine.logList) { Text(it, color = Color.DarkGray, fontSize = 10.sp, modifier = Modifier.padding(4.dp)) }
                }
            }
        }
    }
}

@Composable
fun InfoTile(label: String, valStr: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 10.sp)
        Text(valStr, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ActionBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(60.dp).background(if(active) Color.Red else Color.DarkGray, CircleShape)) {
        Icon(icon, null, tint = Color.White)
    }
}

// --- 4. START ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = AppEngine(this)
        setContent { ScooterProUI(engine) }
    }
}
