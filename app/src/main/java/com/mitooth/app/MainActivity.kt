
package com.mitooth.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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

private val Green = Color(0xFF155B45)
private val Cream = Color(0xFFF5F3E9)

class MainActivity : ComponentActivity() {

    private var connection: BluetoothConnection? = null
    private var receiverRegistered = false

    private val devices = mutableStateListOf<BluetoothDevice>()
    private val messages = mutableStateListOf<String>()
    private val status = mutableStateOf("Bağlantı bekleniyor")

    private val adapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java).adapter
    }

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
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE
                        )
                    }

                device?.let {
                    if (devices.none { item -> item.address == it.address }) {
                        devices.add(it)
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupConnection() {
        if (connection != null) return

        val bluetooth = adapter ?: return

        connection = BluetoothConnection(
            adapter = bluetooth,
            onStatus = { newStatus ->
                status.value = newStatus
            },
            onMessage = { message ->
                messages.add("Karşı taraf: $message")

                if (
                    Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationHelper.showMessage(this, message)
                }
            }
        )
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasBluetoothPermissions()) {
            status.value = "Bluetooth izinleri gerekli"
            return
        }

        val bluetooth = adapter ?: run {
            status.value = "Bluetooth desteklenmiyor"
            return
        }

        if (!bluetooth.isEnabled) {
            status.value = "Önce Bluetooth'u aç"
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        try {
            registerDiscoveryReceiver()
            devices.clear()
            bluetooth.cancelDiscovery()
            bluetooth.startDiscovery()
            status.value = "Yakındaki cihazlar aranıyor..."
        } catch (e: Exception) {
            status.value = "Arama hatası: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWaiting() {
        if (!hasBluetoothPermissions()) {
            status.value = "Bluetooth izinleri gerekli"
            return
        }

        val bluetooth = adapter ?: return

        if (!bluetooth.isEnabled) {
            status.value = "Önce Bluetooth'u aç"
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        setupConnection()

        try {
            connection?.startServer()

            val discoverableIntent = Intent(
                BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE
            ).apply {
                putExtra(
                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                    300
                )
            }

            startActivity(discoverableIntent)
            status.value = "Bağlantı bekleniyor..."
        } catch (e: Exception) {
            status.value = "Bekleme hatası: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) return

        setupConnection()
        adapter?.cancelDiscovery()
        connection?.connect(device)
    }

    private fun sendMessage(message: String) {
        if (message.isBlank()) return

        if (connection == null) {
            status.value = "Önce bir cihaza bağlan"
            return
        }

        connection?.send(message)
        messages.add("Ben: $message")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannel(this)

        setContent {
            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasBluetoothPermissions()) {
                        status.value = "İzinler hazır. İşlem düğmesine tekrar bas."
                    } else {
                        status.value = "Gerekli Bluetooth izinleri verilmedi"
                    }
                }

            MitoothScreen(
                status = status.value,
                devices = devices.toList(),
                messages = messages.toList(),
                onScan = {
                    if (hasBluetoothPermissions()) {
                        startScanning()
                    } else {
                        permissionLauncher.launch(requiredPermissions())
                    }
                },
                onWait = {
                    if (hasBluetoothPermissions()) {
                        startWaiting()
                    } else {
                        permissionLauncher.launch(requiredPermissions())
                    }
                },
                onConnect = { connectTo(it) },
                onSend = { sendMessage(it) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (receiverRegistered) {
                unregisterReceiver(receiver)
                receiverRegistered = false
            }
        } catch (_: Exception) {
        }

        connection?.close()
        connection = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitoothScreen(
    status: String,
    devices: List<BluetoothDevice>,
    messages: List<String>,
    onScan: () -> Unit,
    onWait: () -> Unit,
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
                    containerColor = Green
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
            Text(
                "● $status",
                color = Green,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onWait,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green
                )
            ) {
                Text("Bağlantı bekle")
            }

            OutlinedButton(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text("Cihazları bul")
            }

            Text(
                "Yakındaki cihazlar",
                color = Green,
                fontSize = 18.sp
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 160.dp)
            ) {
                items(devices) { device ->
                    DeviceRow(device, onConnect)
                }
            }

            HorizontalDivider()

            Text(
                "Sohbet",
                color = Green,
                fontSize = 20.sp,
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
                            .padding(vertical = 4.dp)
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
                    placeholder = {
                        Text("Mesajını yaz...")
                    },
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
