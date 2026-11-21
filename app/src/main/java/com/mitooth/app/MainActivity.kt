
package com.mitooth.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID

private val Green = Color(0xFF155B45)
private val Cream = Color(0xFFF5F3E9)
private val APP_UUID: UUID =
    UUID.fromString("8b6d4c20-6a41-4c91-9d2b-7f3a2e5c8101")

class MainActivity : ComponentActivity() {

    private val adapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BluetoothManager::class.java)
        manager.adapter
    }

    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = mutableStateListOf<String>()
    private val _status = mutableStateOf("Bağlantı bekleniyor")
    private val _devices = mutableStateListOf<BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                device?.let {
                    if (_devices.none { d -> d.address == it.address }) {
                        _devices.add(it)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MitoothScreen(
                status = _status.value,
                devices = _devices.toList(),
                messages = _messages.toList(),
                onScan = { startScan() },
                onConnect = { connect(it) },
                onSend = { sendMessage(it) }
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBluetoothPermissions()) {
            _status.value = "Bluetooth izinleri gerekli"
            return
        }

        val bluetooth = adapter ?: run {
            _status.value = "Bu cihaz Bluetooth desteklemiyor"
            return
        }

        if (!bluetooth.isEnabled) {
            _status.value = "Önce Bluetooth'u aç"
            return
        }

        _devices.clear()

        try {
            registerReceiver(
                receiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND)
            )
            bluetooth.cancelDiscovery()
            bluetooth.startDiscovery()
            _status.value = "Cihazlar aranıyor..."
        } catch (e: Exception) {
            _status.value = "Arama başlatılamadı: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) return

        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    _status.value = "Bağlanılıyor..."
                }

                adapter?.cancelDiscovery()

                val newSocket =
                    device.createRfcommSocketToServiceRecord(APP_UUID)

                newSocket.connect()
                socket = newSocket

                withContext(Dispatchers.Main) {
                    _status.value = "Bağlandı: ${device.name ?: "Cihaz"}"
                }

                listenForMessages(newSocket)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _status.value = "Bağlantı başarısız: ${e.message}"
                }
            }
        }
    }

    private suspend fun listenForMessages(activeSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(
                InputStreamReader(activeSocket.inputStream)
            )

            while (currentCoroutineContext().isActive) {
                val message = reader.readLine() ?: break

                withContext(Dispatchers.Main) {
                    _messages.add("Karşı taraf: $message")
                }
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                _status.value = "Bağlantı kesildi"
            }
        }
    }

    private fun sendMessage(message: String) {
        if (message.isBlank()) return

        scope.launch {
            try {
                val activeSocket = socket
                    ?: throw IllegalStateException("Önce bağlanmalısın")

                val writer = PrintWriter(activeSocket.outputStream, true)
                writer.println(message)

                withContext(Dispatchers.Main) {
                    _messages.add("Ben: $message")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _status.value = "Mesaj gönderilemedi: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }

        scope.cancel()
        try {
            socket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitoothScreen(
    status: String,
    devices: List<BluetoothDevice>,
    messages: List<String>,
    onScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = {
                    Text("Mitooth", color = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Green,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Bluetooth: $status", fontSize = 14.sp)

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text("Yakındaki cihazları bul")
            }

            Text(
                "Bulunan cihazlar",
                fontSize = 18.sp,
                color = Green
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 150.dp)
            ) {
                items(devices) { device ->
                    DeviceRow(device, onConnect)
                }
            }

            HorizontalDivider()

            Text(
                "Mesajlar",
                fontSize = 20.sp,
                color = Green,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mesajını yaz...") },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Gönder",
                        tint = Green
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceRow(
    device: BluetoothDevice,
    onConnect: (BluetoothDevice) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect(device) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, null, tint = Green)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(device.name ?: "İsimsiz cihaz")
            Text(device.address, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("Bağlan", color = Green)
    }
}
