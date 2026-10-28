package com.example.itsover02

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class EvasiveAppBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityBlocker"

        // Instaladores, tiendas de apps y pantallas críticas de configuración de ubicación
        private val STORE_PACKAGES = setOf(
            "com.android.vending",                  // Google Play Store
            "com.google.android.packageinstaller",  // Instalador estándar
            "com.miui.packageinstaller",            // Instalador de Xiaomi / HyperOS
            "com.android.packageinstaller",
            "com.android.settings"                  // Configuración del sistema
        )

        // Paquetes conocidos de suplantación GPS / Mock Location
        private val FAKE_GPS_PACKAGES = setOf(
            "com.lexa.fakegps",
            "com.rosteam.gpsemulator",
            "com.incorporateapps.fakegps.fre",
            "com.fakegps.mock",
            "com.locationchanger",
            "ru.gavrikov.mocklocations"
        )

        // Palabras clave de suplantación y evasión de ubicación
        private val LOCATION_EVASION_KEYWORDS = listOf(
            "fake gps", "mock location", "ubicación simulada", "simular ubicación",
            "falsa ubicación", "cambiar ubicación", "gps spoof"
        )
    }

    private var lastScanTime: Long = 0
    private var adminComponent: ComponentName? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        adminComponent = DeviceAdminReceiver.getComponentName(this)
        Log.i(TAG, "Servicio de Accesibilidad Centinela Conectado y Operativo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val currentPackage = event.packageName?.toString()?.lowercase() ?: return

        // 1. Detección inmediata de aplicaciones Fake GPS / Mock Location
        if (FAKE_GPS_PACKAGES.contains(currentPackage) || AppVetoManager.BLOCKED_KEYWORDS.any { currentPackage.contains(it) }) {
            Log.w(TAG, "APLICACIÓN PROHIBIDA O MOCK GPS DETECTADA ($currentPackage). EXPULSANDO...")
            abortToHome()
            adminComponent?.let { AppVetoManager.vetoPackage(this, it, event.packageName.toString()) }
            return
        }

        // 1.1 Si el dispositivo está dentro de la geocerca de Trujillo o Pacasmayo, expulsar si intentan abrir Brave, YouTube o Lens
        if (BlocklistRepository.isGeofenceRestricted && AppVetoManager.GEOFENCE_APPS.contains(currentPackage)) {
            Log.w(TAG, "APP RESTRINGIDA POR GEOCERCA EN PRIMER PLANO ($currentPackage). EXPULSANDO AL HOME...")
            abortToHome()
            return
        }

        // 1.1 Detección de intentos de acceder a ajustes de ubicación o simulación
        if (currentPackage == "com.android.settings") {
            val rootNode = rootInActiveWindow
            if (rootNode != null && LOCATION_EVASION_KEYWORDS.any { keyword -> containsKeyword(rootNode, keyword) }) {
                Log.w(TAG, "INTENTO DE CONFIGURACIÓN DE MOCK LOCATION DETECTADO. EXPULSANDO...")
                abortToHome()
                return
            }
        }

        // 2. Si el usuario está en la Play Store o en un Instalador de APKs, inspeccionar el contenido de la pantalla
        if (STORE_PACKAGES.contains(currentPackage)) {
            val now = SystemClock.uptimeMillis()
            // Throttle de 300ms para no sobrecargar CPU en animaciones de scroll
            if (now - lastScanTime < 300) return
            lastScanTime = now

            val rootNode = rootInActiveWindow ?: return
            if (containsForbiddenKeyword(rootNode)) {
                Log.w(TAG, "PALABRA CLAVE PROHIBIDA ENCONTRADA EN PANTALLA. EXPULSANDO AL HOME...")
                abortToHome()
                adminComponent?.let { AppVetoManager.scanAndVetoAll(this, it) }
            }
        }
    }

    private fun containsForbiddenKeyword(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDescription = node.contentDescription?.toString()?.lowercase() ?: ""

        if (AppVetoManager.BLOCKED_KEYWORDS.any { text.contains(it) || contentDescription.contains(it) }) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (containsForbiddenKeyword(node.getChild(i))) {
                return true
            }
        }
        return false
    }

    private fun abortToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido.")
    }

    private fun containsKeyword(node: AccessibilityNodeInfo, keyword: String): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsKeyword(child, keyword)) return true
        }
        return false
    }
}