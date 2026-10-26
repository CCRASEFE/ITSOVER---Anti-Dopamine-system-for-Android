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

        // Instaladores y tiendas de apps supervisadas
        private val STORE_PACKAGES = setOf(
            "com.android.vending",                  // Google Play Store
            "com.google.android.packageinstaller",  // Instalador estándar
            "com.miui.packageinstaller",            // Instalador de Xiaomi / HyperOS
            "com.android.packageinstaller"
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

        // 1. Si la ventana activa es DIRECTAMENTE cualquier versión de Opera, expulsar al instante
        if (AppVetoManager.BLOCKED_KEYWORDS.any { currentPackage.contains(it) }) {
            Log.w(TAG, "OPERA DETECTADO EN PRIMER PLANO ($currentPackage). EXPULSANDO...")
            abortToHome()
            adminComponent?.let { AppVetoManager.vetoPackage(this, it, event.packageName.toString()) }
            return
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
}