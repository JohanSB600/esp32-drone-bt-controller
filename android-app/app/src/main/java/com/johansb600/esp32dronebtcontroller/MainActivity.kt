package com.johansb600.esp32dronebtcontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.johansb600.esp32dronebtcontroller.ui.theme.Esp32DroneBtControllerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

class MainActivity : ComponentActivity() {

    private val btPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Opcional: puedes avisar si falló alguno
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user may accept/deny */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Esp32DroneBtControllerTheme {
                MainScreen(
                    onRequestBtPermissions = {
                        btPermissionsLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                        )
                    },
                    onRequestEnableBluetooth = {
                        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBtLauncher.launch(intent)
                    }
                )
            }
        }
    }
}

private const val TARGET_DEVICE_NAME = "ESP32_Puerta"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onRequestBtPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isConnected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Desconectado") }
    var socket by remember { mutableStateOf<BluetoothSocket?>(null) }

    // Verifica ambos permisos
    fun hasBtPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun getAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    suspend fun connect() {
        try {
            val adapter = getAdapter()
            if (adapter == null) {
                status = "Bluetooth no disponible"
                snackbarHostState.showSnackbar("Este dispositivo no tiene Bluetooth")
                return
            }

            if (!hasBtPermissions()) {
                onRequestBtPermissions()
                snackbarHostState.showSnackbar("Concede permisos Bluetooth para conectar.")
                return
            }

            if (!adapter.isEnabled) {
                onRequestEnableBluetooth()
                snackbarHostState.showSnackbar("Activa Bluetooth para conectar")
                return
            }

            val bonded = adapter.bondedDevices
            val device: BluetoothDevice? = bonded.firstOrNull { it.name == TARGET_DEVICE_NAME }

            if (device == null) {
                status = "No emparejado: $TARGET_DEVICE_NAME"
                snackbarHostState.showSnackbar("Empareja primero '$TARGET_DEVICE_NAME' en Ajustes > Bluetooth")
                return
            }

            status = "Conectando a $TARGET_DEVICE_NAME..."

            withContext(Dispatchers.IO) {
                try {
                    adapter.cancelDiscovery()
                    val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    tmpSocket.connect()

                    socket = tmpSocket
                    isConnected = true
                    status = "Conectado a $TARGET_DEVICE_NAME"
                } catch (e: IOException) {
                    socket = null
                    isConnected = false
                    status = "Error de conexión"
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar("No se pudo conectar: ${e.message ?: "error"}")
                    }
                }
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Error fatal: ${e.message ?: "error"}")
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                socket?.close()
            } catch (_: IOException) { }
            finally {
                socket = null
                isConnected = false
                status = "Desconectado"
            }
        }
    }

    suspend fun sendCommand(cmd: Char) {
        val s = socket
        if (!isConnected || s == null) {
            snackbarHostState.showSnackbar("No estás conectado")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                s.outputStream.write(byteArrayOf(cmd.code.toByte()))
                s.outputStream.flush()
            } catch (e: IOException) {
                isConnected = false
                status = "Desconectado (error)"
                socket = null
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("Error enviando comando: ${e.message ?: "error"}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasBtPermissions()) {
            snackbarHostState.showSnackbar("La app necesita permisos Bluetooth para conectar")
        }
    }

    // UI
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control Dron (Bluetooth)") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Estado: $status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            if (isConnected) disconnect() else connect()
                        }
                    }
                ) {
                    Text(if (isConnected) "DESCONECTAR" else "CONECTAR")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            if (!hasBtPermissions()) {
                                onRequestBtPermissions()
                            } else {
                                snackbarHostState.showSnackbar("Permiso Bluetooth: OK")
                            }
                        }
                    }
                ) {
                    Text("PERMISO")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Comandos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('A') } }
            ) { Text("ENCENDER MOTORES") }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('B') } }
            ) { Text("APAGAR MOTORES") }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('R') } }
            ) { Text("REVERSA") }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('C') } }
            ) { Text("ABRIR PUERTA") }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('D') } }
            ) { Text("CERRAR PUERTA") }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { sendCommand('E') } }
            ) { Text("STOP TOTAL") }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Consejo: si no conecta, verifica que '$TARGET_DEVICE_NAME' esté emparejado en Ajustes > Bluetooth.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}