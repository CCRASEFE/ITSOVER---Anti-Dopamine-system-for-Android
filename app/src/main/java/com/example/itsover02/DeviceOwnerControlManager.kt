package com.example.itsover02

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

object DeviceOwnerControlManager {

    private const val TAG = "DOControlManager"

    // Lista negra de navegadores que utilizan proxies/VPNs propietarios no gestionables
    val EVASIVE_BROWSER_PACKAGES = arrayOf(
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.browser.beta",
        "com.opera.touch",
        "org.torproject.torbrowser"
    )

    /**
     * Aplica restricciones del sistema para evitar que el usuario instale APKs
     * o navegadores fuera de canales aprobados.
     */
    fun enforceSystemLockdown(context: Context, admin: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            // Prohíbe la instalación de aplicaciones desde fuentes desconocidas (APKs descargados)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            Log.i(TAG, "Restricción de fuentes desconocidas aplicada.")
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando restricciones del sistema", e)
        }
    }

    /**
     * Oculta y congela una aplicación evasiva. La app desaparece del lanzador,
     * no puede correr servicios en background ni ejecutar procesos.
     */
    fun setAppQuarantine(context: Context, admin: ComponentName, packageName: String, hide: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            val success = dpm.setApplicationHidden(admin, packageName, hide)
            Log.i(TAG, "Estado de cuarentena para $packageName ($hide): $success")
        } catch (e: Exception) {
            Log.e(TAG, "Error modificando estado de aplicación para $packageName", e)
        }
    }

    /**
     * Congela automáticamente cualquier navegador evasivo instalado en el dispositivo.
     */
    fun quarantineAllEvasiveBrowsers(context: Context, admin: ComponentName) {
        for (pkg in EVASIVE_BROWSER_PACKAGES) {
            setAppQuarantine(context, admin, pkg, true)
        }
    }
}