package com.example.itsover02

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object BlocklistRepository {

    private const val TAG = "BlocklistRepo"

    private val BLOCKED_DOMAINS = HashSet<String>(15000)

    // CAPA 1: Dominio Canario de Mozilla / Chromium
    private val CANARY_DOMAINS: Set<String> = hashSetOf(
        "use-application-dns.net"
    )

    // CAPA 2: Resolvers DoH conocidos
    private val DOH_BOOTSTRAP_DOMAINS: Set<String> = hashSetOf(
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "dns.google",
        "dns.google.com",
        "dns.quad9.net",
        "doh.mullvad.net",
        "dns.adguard-dns.com",
        "doh.cleanbrowsing.org",
        "doh.opendns.com",
        "dns.nextdns.io"
    )

    // CAPA OPERA: Infraestructura de proxy TLS/SOCKS5 y VPN SurfEasy de Opera
    private val OPERA_PROXY_DOMAINS: Set<String> = hashSetOf(
        "net.opera.com",
        "opera-mini.net",
        "site-proxy.opera-api.com",
        "vpn.opera.com",
        "global-zone.opera-api.com",
        "opera-api.com",
        "api.opera.com",
        "surfeasy.com",
        "surfeasy.net",
        "api.surfeasy.com",
        "edge-cache.opera.com"
    )
    // DOMINIOS CONDICIONALES: Solo se bloquean si el GPS está DENTRO de Trujillo o Pacasmayo
    private val GEOFENCE_CONDITIONAL_DOMAINS: Set<String> = hashSetOf(
        "youtube.com",
        "youtu.be",
        "googlevideo.com",
        "ytimg.com",
        "youtubei.googleapis.com",
        "brave.com",
        "search.brave.com",
        "lens.google.com",
        "lens.google"
    )

    val BYPASS_IPS: Set<String> = hashSetOf(
        "1.1.1.1", "1.0.0.1",
        "8.8.8.8", "8.8.4.4",
        "9.9.9.9", "149.112.112.11",
        "208.67.222.222", "208.67.220.220"
    )

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var isGeofenceRestricted: Boolean = false

    fun loadFromAssets(context: Context) {
        if (isLoaded) return

        synchronized(this) {
            if (isLoaded) return

            try {
                context.assets.open("blocklist.txt").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                        for (rawLine in lines) {
                            val line = rawLine.trim()
                            if (line.isEmpty() || line.startsWith("#")) continue

                            val parts = line.split("\\s+".toRegex())
                            val domain = parts.last().lowercase().trimEnd('.')

                            if (domain.isNotEmpty()) {
                                BLOCKED_DOMAINS.add(domain)
                            }
                        }
                    }
                }
                isLoaded = true
                Log.i(TAG, "Lista cargada: ${BLOCKED_DOMAINS.size} dominios en memoria.")
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo assets/blocklist.txt", e)
            }
        }
    }

    fun isBlocked(domain: String): Boolean {
        if (domain.isBlank()) return false
        val normalized = domain.lowercase().trimEnd('.')

        // 1. Dominio Canario DoH
        if (matchesDomainOrSubdomain(normalized, CANARY_DOMAINS)) {
            Log.w(TAG, "CANARY TRIGGERED: $normalized -> Forzando desactivación de DoH")
            return true
        }

        // 2. Infraestructura Proxy/VPN Opera
        if (matchesDomainOrSubdomain(normalized, OPERA_PROXY_DOMAINS)) {
            Log.w(TAG, "OPERA VPN/PROXY BLOCKED: $normalized -> Handshake abortado")
            return true
        }

        // 3. Resolvers DoH
        if (matchesDomainOrSubdomain(normalized, DOH_BOOTSTRAP_DOMAINS)) {
            Log.w(TAG, "DOH BOOTSTRAP BLOCKED: $normalized")
            return true
        }

        // 4. Filtrado forzado si el dispositivo se encuentra DENTRO de la geocerca
        // 4. Filtrado CONDICIONAL: Solo si el dispositivo está DENTRO de la geocerca
        // Si estás fuera de Trujillo/Pacasmayo, isGeofenceRestricted es false y este bloque se omite
        if (isGeofenceRestricted && matchesDomainOrSubdomain(normalized, GEOFENCE_CONDITIONAL_DOMAINS)) {
            Log.w(TAG, "SINKHOLE CONDICIONAL: $normalized bloqueado por geocerca activa.")
            return true
        }

        // 5. Lista negra masiva PERMANENTE (Los 11.000 dominios de assets/blocklist.txt)
        if (isLoaded && matchesDomainOrSubdomain(normalized, BLOCKED_DOMAINS)) {
            return true
        }

        return false
    }

    private fun matchesDomainOrSubdomain(target: String, sourceSet: Set<String>): Boolean {
        if (sourceSet.contains(target)) return true

        var dotIndex = target.indexOf('.')
        while (dotIndex != -1) {
            val parentDomain = target.substring(dotIndex + 1)
            if (sourceSet.contains(parentDomain)) {
                return true
            }
            dotIndex = target.indexOf('.', dotIndex + 1)
        }
        return false
    }
}