package com.example.itsover02

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class LocalDnsBlockerService : VpnService() {

    companion object {
        private const val TAG = "LocalDnsVpn"
        private const val CHANNEL_ID = "dns_sinkhole_channel"
        private const val NOTIFICATION_ID = 9001
        private const val VPN_MTU = 32767
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val DNS_PORT = 53

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var outputStream: FileOutputStream? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            setUnderlyingNetworks(arrayOf(network))
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            setUnderlyingNetworks(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerDefaultNetworkCallback()
        BlocklistRepository.loadFromAssets(applicationContext)

        // Iniciar monitoreo activo de geocercas
        LocationGeofenceEngine.startMonitoring(applicationContext)
    }

    private fun registerDefaultNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (!_isRunning.value) {
            serviceScope.launch {
                val admin = DeviceAdminReceiver.getComponentName(this@LocalDnsBlockerService)
                launch(Dispatchers.Default) {
                    while (isActive) {
                        AppVetoManager.scanAndVetoAll(this@LocalDnsBlockerService, admin)
                        delay(5000L)
                    }
                }

                val pfd = establishVpnWithRetry(maxRetries = 10, delayMillis = 1500L)
                if (pfd != null) {
                    vpnInterface = pfd
                    _isRunning.value = true
                    startPacketProcessing(pfd)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /**
     * Establece el túnel con configuración DUAL-STACK (IPv4 + IPv6).
     */
    private suspend fun establishVpnWithRetry(maxRetries: Int, delayMillis: Long): ParcelFileDescriptor? {
        for (attempt in 1..maxRetries) {
            try {
                val builder = Builder()
                    .setSession("DNS Sinkhole")
                    .setMtu(VPN_MTU)
                    // Configuración IPv4
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("10.0.0.1")
                    .addRoute("10.0.0.1", 32)
                    // Configuración IPv6 (ULA: Unique Local Address)
                    .addAddress("fd00::2", 128)
                    .addDnsServer("fd00::1")
                    .addRoute("fd00::1", 128)
                    .allowBypass()

                val pfd = builder.establish()
                if (pfd != null) {
                    Log.i(TAG, "Túnel VPN Dual-Stack (IPv4/IPv6) establecido exitosamente.")
                    return pfd
                }
            } catch (e: Exception) {
                Log.w(TAG, "Intento $attempt falló al establecer VPN: ${e.message}")
            }
            delay(delayMillis)
        }
        return null
    }

    private fun startPacketProcessing(pfd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(pfd.fileDescriptor)
        outputStream = FileOutputStream(pfd.fileDescriptor)

        serviceScope.launch(Dispatchers.IO) {
            val packet = ByteArray(VPN_MTU)

            while (isActive) {
                val length = try {
                    inputStream.read(packet)
                } catch (e: Exception) {
                    break
                }

                if (length <= 0) continue

                val packetData = packet.copyOf(length)
                serviceScope.launch(Dispatchers.IO) {
                    handlePacket(packetData)
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        val buffer = ByteBuffer.wrap(packet)

        val version = (buffer.get(0).toInt() shr 4) and 0x0F

        if (version == 4) {
            handleIPv4Packet(packet, buffer)
        } else if (version == 6) {
            handleIPv6Packet(packet, buffer)
        }
    }

    // --- PROCESAMIENTO IPv4 ---
    private fun handleIPv4Packet(packet: ByteArray, buffer: ByteBuffer) {
        val ihl = (buffer.get(0).toInt() and 0x0F) * 4
        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return // Solo UDP

        val srcIp = ByteArray(4).also { buffer.position(12); buffer.get(it) }
        val dstIp = ByteArray(4).also { buffer.position(16); buffer.get(it) }

        buffer.position(ihl)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF

        if (dstPort == DNS_PORT) {
            val dnsOffset = ihl + 8
            val dnsPayloadLength = packet.size - dnsOffset
            if (dnsPayloadLength < 12) return

            val dnsPayload = ByteArray(dnsPayloadLength)
            System.arraycopy(packet, dnsOffset, dnsPayload, 0, dnsPayloadLength)

            processDnsPayload(dnsPayload) { responsePayload ->
                buildUdpIpv4Packet(dstIp, srcIp, dstPort, srcPort, responsePayload)
            }
        }
    }

    // --- PROCESAMIENTO IPv6 (Cierra la fuga de IPv6) ---
    private fun handleIPv6Packet(packet: ByteArray, buffer: ByteBuffer) {
        if (packet.size < 48) return // 40 bytes de cabecera IPv6 + 8 bytes de cabecera UDP

        val nextHeader = buffer.get(6).toInt() and 0xFF
        if (nextHeader != 17) return // Solo UDP

        val srcIp = ByteArray(16).also { buffer.position(8); buffer.get(it) }
        val dstIp = ByteArray(16).also { buffer.position(24); buffer.get(it) }

        buffer.position(40)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF

        if (dstPort == DNS_PORT) {
            val dnsOffset = 48
            val dnsPayloadLength = packet.size - dnsOffset
            if (dnsPayloadLength < 12) return

            val dnsPayload = ByteArray(dnsPayloadLength)
            System.arraycopy(packet, dnsOffset, dnsPayload, 0, dnsPayloadLength)

            processDnsPayload(dnsPayload) { responsePayload ->
                buildUdpIpv6Packet(dstIp, srcIp, dstPort, srcPort, responsePayload)
            }
        }
    }

    private inline fun processDnsPayload(
        dnsPayload: ByteArray,
        crossinline packetBuilder: (ByteArray) -> ByteArray
    ) {
        val domain = extractQName(dnsPayload)

        // 1. PRIORIDAD: Redirección DNS oficial de Google SafeSearch (forcesafesearch.google.com)
        if (domain != null && SafeSearchRepository.isGoogleSearchDomain(domain)) {
            val safeSearchResponse = SafeSearchRepository.createSafeSearchResponse(dnsPayload)
            if (safeSearchResponse != null) {
                Log.i(TAG, "SAFESEARCH ENFORCED: $domain -> 216.239.38.120 (forcesafesearch)")
                val responsePacket = packetBuilder(safeSearchResponse)
                synchronized(this) {
                    try {
                        outputStream?.write(responsePacket)
                        outputStream?.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error escribiendo respuesta SafeSearch", e)
                    }
                }
                return
            }
        }

        // 2. Evaluación de Lista Negra (Sinkhole)
        if (domain != null && BlocklistRepository.isBlocked(domain)) {
            Log.i(TAG, "SINKHOLE BLOQUEADO: $domain -> NXDOMAIN")
            val responsePayload = createNxDomainResponse(dnsPayload)
            val responsePacket = packetBuilder(responsePayload)
            synchronized(this) {
                try {
                    outputStream?.write(responsePacket)
                    outputStream?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error escribiendo respuesta sinkhole", e)
                }
            }
        } else {
            forwardDnsQuery(dnsPayload, packetBuilder)
        }
    }

    private inline fun forwardDnsQuery(
        dnsPayload: ByteArray,
        crossinline packetBuilder: (ByteArray) -> ByteArray
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2500

            val upstreamIp = InetAddress.getByName(UPSTREAM_DNS)
            val outPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstreamIp, DNS_PORT)
            socket.send(outPacket)

            val receiveBuffer = ByteArray(4096)
            val inPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(inPacket)

            val realResponse = ByteArray(inPacket.length)
            System.arraycopy(receiveBuffer, 0, realResponse, 0, inPacket.length)

            val responsePacket = packetBuilder(realResponse)

            synchronized(this) {
                outputStream?.write(responsePacket)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolviendo upstream: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun extractQName(dnsPayload: ByteArray): String? {
        var offset = 12
        val domain = StringBuilder()

        while (offset < dnsPayload.size) {
            val labelLen = dnsPayload[offset].toInt() and 0xFF
            if (labelLen == 0) break
            if ((labelLen and 0xC0) == 0xC0) return null

            offset++
            if (offset + labelLen > dnsPayload.size) return null

            if (domain.isNotEmpty()) domain.append(".")
            for (i in 0 until labelLen) {
                domain.append(dnsPayload[offset + i].toInt().toChar())
            }
            offset += labelLen
        }
        return domain.toString()
    }

    private fun createNxDomainResponse(dnsQuery: ByteArray): ByteArray {
        val response = dnsQuery.clone()
        if (response.size < 12) return response

        response[2] = 0x81.toByte()
        response[3] = 0x83.toByte()
        response[6] = 0x00
        response[7] = 0x00
        response[8] = 0x00
        response[9] = 0x00
        response[10] = 0x00
        response[11] = 0x00

        return response
    }

    // --- CONSTRUCTOR IPv4 ---
    private fun buildUdpIpv4Packet(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLength.toShort())
        buf.putShort(0x0000.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0x0000.toShort())
        buf.put(srcIp)
        buf.put(dstIp)

        val ipChecksum = computeIpChecksum(packet, 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0x0000.toShort())
        buf.put(payload)

        return packet
    }

    // --- CONSTRUCTOR IPv6 CON CHECKSUM OBLIGATORIO (RFC 8200) ---
    private fun buildUdpIpv6Packet(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 40 + udpLength
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        // Cabecera IPv6 (40 bytes fijos)
        buf.putInt(0x60000000) // Versión 6, Traffic Class 0, Flow Label 0
        buf.putShort(udpLength.toShort()) // Longitud del Payload UDP
        buf.put(17.toByte()) // Next Header = UDP
        buf.put(64.toByte()) // Hop Limit
        buf.put(srcIp) // 16 bytes origen
        buf.put(dstIp) // 16 bytes destino

        // Cabecera UDP (8 bytes)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0x0000.toShort()) // Placeholder para Checksum
        buf.put(payload)

        // En IPv6, el Checksum UDP es OBLIGATORIO sobre la pseudo-cabecera
        val checksum = computeUdpIpv6Checksum(srcIp, dstIp, udpLength, packet, 40, udpLength)
        buf.putShort(46, checksum.toShort())

        return packet
    }

    private fun computeUdpIpv6Checksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLength: Int,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0

        // 1. Pseudo-cabecera: IP Origen (8 palabras de 16 bits)
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        // 2. Pseudo-cabecera: IP Destino (8 palabras de 16 bits)
        for (i in 0 until 16 step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        // 3. Longitud UDP (32 bits)
        sum += (udpLength shr 16) and 0xFFFF
        sum += udpLength and 0xFFFF
        // 4. Next Header (17 = UDP)
        sum += 17

        // 5. Cabecera UDP + Payload
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        var result = sum.inv() and 0xFFFF
        if (result == 0) result = 0xFFFF
        return result
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Protection Persistence",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Filtrado DNS local activo."
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DNS Sinkhole Activo")
            .setContentText("Filtrando peticiones en tiempo real (IPv4/IPv6).")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        _isRunning.value = false
        serviceScope.cancel()
        LocationGeofenceEngine.stopMonitoring()
        val cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}