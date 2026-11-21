package com.mitooth.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.UUID

class BluetoothConnection(
    private val adapter: BluetoothAdapter,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        val APP_UUID: UUID =
            UUID.fromString("8b6d4c20-6a41-4c91-9d2b-7f3a2e5c8101")

        const val SERVICE_NAME = "Mitooth"
    }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var socket: BluetoothSocket? = null

    private var serverSocket: android.bluetooth.BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var readerJob: Job? = null

    private val writeLock = Any()

    private suspend fun updateStatus(message: String) {
        withContext(Dispatchers.Main) {
            onStatus(message)
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        serverJob?.cancel()

        serverJob = scope.launch {
            try {
                serverSocket =
                    adapter.listenUsingRfcommWithServiceRecord(
                        SERVICE_NAME,
                        APP_UUID
                    )

                updateStatus("Bağlantı bekleniyor...")

                val accepted = serverSocket?.accept()
                    ?: throw IllegalStateException("Bağlantı kurulamadı")

                socket = accepted

                serverSocket?.close()
                serverSocket = null

                updateStatus(
                    "Bağlandı: ${accepted.remoteDevice.name ?: "Cihaz"}"
                )

                listen(accepted)

            } catch (e: Exception) {
                if (isActive) {
                    updateStatus("Sunucu hatası: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        serverJob?.cancel()

        scope.launch {
            try {
                updateStatus("Bağlanılıyor...")

                adapter.cancelDiscovery()

                val newSocket =
                    device.createRfcommSocketToServiceRecord(APP_UUID)

                newSocket.connect()
                socket = newSocket

                updateStatus("Bluetooth bağlantısı kuruldu")

                listen(newSocket)

            } catch (e: Exception) {
                updateStatus("Bağlantı hatası: ${e.message}")
            }
        }
    }

    private fun listen(activeSocket: BluetoothSocket) {
        readerJob?.cancel()

        readerJob = scope.launch {
            try {
                val reader = BufferedReader(
                    InputStreamReader(
                        activeSocket.inputStream,
                        Charsets.UTF_8
                    )
                )

                while (isActive) {
                    val message = reader.readLine() ?: break

                    withContext(Dispatchers.Main) {
                        onMessage(message)
                    }
                }

                updateStatus("Bağlantı kapandı")

            } catch (e: Exception) {
                updateStatus("Bağlantı kesildi")

            } finally {
                try {
                    activeSocket.close()
                } catch (_: Exception) {
                }

                if (socket === activeSocket) {
                    socket = null
                }
            }
        }
    }

    fun send(message: String) {
        if (message.isBlank()) return

        scope.launch {
            try {
                val activeSocket = socket
                    ?: throw IllegalStateException(
                        "Bağlı cihaz bulunamadı"
                    )

                synchronized(writeLock) {
                    val writer = PrintWriter(
                        OutputStreamWriter(
                            activeSocket.outputStream,
                            Charsets.UTF_8
                        ),
                        true
                    )

                    writer.println(message)

                    if (writer.checkError()) {
                        throw IllegalStateException(
                            "Mesaj gönderilemedi"
                        )
                    }
                }

            } catch (e: Exception) {
                updateStatus("Gönderim hatası: ${e.message}")
            }
        }
    }

    fun close() {
        readerJob?.cancel()
        serverJob?.cancel()

        try {
            socket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }

        socket = null
        serverSocket = null

        scope.cancel()
    }
}