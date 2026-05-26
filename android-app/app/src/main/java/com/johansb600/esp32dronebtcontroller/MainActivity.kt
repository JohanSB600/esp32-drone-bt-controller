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
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.johansb600.esp32dronebtcontroller.ui.theme.Esp32DroneBtControllerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled in UI state */ }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user may accept/deny */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Esp32DroneBtControllerTheme {
                MainScreen(
                    onRequestBtPermission = {
                        btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
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

// UUID estándar para SPP (Serial Port Profile)
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.S)
@androidx.compose.runtime.Composable
private fun MainScreen(
    onRequestBtPermission: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isConnected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Desconectado") }

    // Keep socket in state
    var socket by remember { mutableStateOf<BluetoothSocket?>(null) }

    fun hasBtConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    suspend fun connect() {
        val adapter = getAdapter()
        if (adapter == null) {
            status = "Bluetooth no disponible"
            snackbarHostState.showSnackbar("Este dispositivo no tiene Bluetooth")
            return
        }

        if (!hasBtConnectPermission()) {
            onRequestBtPermission()
            snackbarHostState.showSnackbar("Concede permiso de Bluetooth para conectar")
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
                // cancelar descubrimiento acelera conexión
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
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                socket?.close()
            } catch (_: IOException) {
            } finally {
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

    // Si el usuario concede permiso, actualizamos el snackbar una vez
    LaunchedEffect(Unit) {
        if (!hasBtConnectPermission()) {
            snackbarHostState.showSnackbar("La app necesita permiso Bluetooth para conectar")
        }
    }

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
                            if (!hasBtConnectPermission()) {
                                onRequestBtPermission()
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
