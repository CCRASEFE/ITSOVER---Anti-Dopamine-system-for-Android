package com.example.itsover02

import android.util.Log

object SafeSearchRepository {

    private const val TAG = "SafeSearchRepo"

    // IPs oficiales de forcesafesearch.google.com
    val FORCESAFESEARCH_IPV4 = byteArrayOf(
        216.toByte(), 239.toByte(), 38.toByte(), 120.toByte() // 216.239.38.120
    )

    val FORCESAFESEARCH_IPV6 = byteArrayOf(
        0x20.toByte(), 0x01.toByte(), 0x48.toByte(), 0x60.toByte(),
        0x48.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x78.toByte() // 2001:4860:4806::78
    )

    // Regex que intercepta exclusivamente dominios de búsqueda (google.com, www.google.com, google.com.pe, google.es)
    // sin romper servicios como drive.google.com, accounts.google.com o mail.google.com
    private val GOOGLE_SEARCH_REGEX = Regex(
        "^(www\\.)?google\\.(com|[a-z]{2}|com\\.[a-z]{2}|co\\.[a-z]{2})$",
        RegexOption.IGNORE_CASE
    )

    fun isGoogleSearchDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        val normalized = domain.lowercase().trimEnd('.')
        return GOOGLE_SEARCH_REGEX.matches(normalized)
    }

    /**
     * Ensambla una respuesta DNS sintética RFC 1035 inyectando la IP de SafeSearch
     * tanto para consultas IPv4 (A = 1) como para IPv6 (AAAA = 28).
     */
    fun createSafeSearchResponse(dnsQuery: ByteArray): ByteArray? {
        if (dnsQuery.size < 12) return null

        // 1. Localizar el final de la sección Question (QNAME + QTYPE + QCLASS)
        var offset = 12
        while (offset < dnsQuery.size) {
            val len = dnsQuery[offset].toInt() and 0xFF
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                offset += 2
                break
            }
            offset += 1 + len
        }

        if (offset + 4 > dnsQuery.size) return null

        val qType = ((dnsQuery[offset].toInt() and 0xFF) shl 8) or (dnsQuery[offset + 1].toInt() and 0xFF)
        val questionEnd = offset + 4 // Termina la sección Question

        val isTypeA = (qType == 1)      // Consulta IPv4
        val isTypeAAAA = (qType == 28)  // Consulta IPv6

        val rdata = when {
            isTypeA -> FORCESAFESEARCH_IPV4
            isTypeAAAA -> FORCESAFESEARCH_IPV6
            else -> null // Para otros tipos (TXT, HTTPS/SVCB), se retorna respuesta sin answers para forzar fallback a A/AAAA
        }

        val answerSize = if (rdata != null) {
            2 + 2 + 2 + 4 + 2 + rdata.size // Pointer (2) + Type (2) + Class (2) + TTL (4) + RdLength (2) + RData
        } else {
            0
        }

        val totalLength = questionEnd + answerSize
        val response = ByteArray(totalLength)

        // Copiar encabezado y sección Question intactos
        System.arraycopy(dnsQuery, 0, response, 0, questionEnd)

        // Flags: 0x8180 (Response, Recursion Available, No Error)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()

        // ANCOUNT: 1 si inyectamos respuesta, 0 si es NOERROR vacío
        response[6] = 0x00
        response[7] = (if (rdata != null) 1 else 0).toByte()

        // NSCOUNT = 0, ARCOUNT = 0
        response[8] = 0x00; response[9] = 0x00
        response[10] = 0x00; response[11] = 0x00

        // Si hay respuesta RDATA (A o AAAA), escribir la sección Answer
        if (rdata != null) {
            var out = questionEnd

            // Name: Puntero de compresión hacia el QNAME inicial (offset 12 -> 0xC00C)
            response[out++] = 0xC0.toByte()
            response[out++] = 0x0C.toByte()

            // Type
            response[out++] = (qType shr 8).toByte()
            response[out++] = (qType and 0xFF).toByte()

            // Class: IN (0x0001)
            response[out++] = 0x00
            response[out++] = 0x01

            // TTL: 300 segundos (0x0000012C)
            response[out++] = 0x00
            response[out++] = 0x00
            response[out++] = 0x01
            response[out++] = 0x2C.toByte()

            // RDLENGTH
            response[out++] = (rdata.size shr 8).toByte()
            response[out++] = (rdata.size and 0xFF).toByte()

            // RDATA (IP)
            System.arraycopy(rdata, 0, response, out, rdata.size)
        }

        return response
    }
}