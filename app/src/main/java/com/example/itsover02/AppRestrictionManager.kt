package com.example.itsover02

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.UserManager
import android.util.Log

object AppRestrictionManager {

    private const val TAG = "AppRestrictionManager"

    // Cubrimos TODAS las variantes de Opera y navegadores con proxies/VPNs internas
    val EVASIVE_PACKAGES: Set<String> = hashSetOf(
        "com.opera.browser",              // Opera Estándar
        "com.opera.gx",                   // Opera GX (Gaming)
        "com.opera.mini.native",          // Opera Mini
        "com.opera.browser.beta",         // Opera Beta
        "com.opera.touch",                // Opera Touch
        "com.opera.cryptobrowser",        // Opera Crypto
        "org.torproject.torbrowser",      // Tor Browser Oficial
        "com.torproject.torbrowser",
        "org.torproject.torbrowser_alpha"
    )

    fun applyAllRestrictions(context: Context, admin: ComponentName) {
        enforceInstallRestrictions(context, admin)
        quarantineAllEvasivePackages(context, admin)
    }

    fun enforceInstallRestrictions(context: Context, admin: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            Log.i(TAG, "Restricción DISALLOW_INSTALL_UNKNOWN_SOURCES aplicada.")
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando restricción de instalación", e)
        }
    }

    fun quarantineAllEvasivePackages(context: Context, admin: ComponentName) {
        for (pkg in EVASIVE_PACKAGES) {
            vetoPackage(context, admin, pkg)
        }
    }

    /**
     * VETA EL PAQUETE MEDIANTE TRIPLE ACCIÓN MDM:
     * 1. Suspender (mata el proceso de inmediato e impide abrirlo desde Play Store).
     * 2. Ocultar (elimina el icono del launcher).
     * 3. Desinstalación silenciosa automática (elimina el paquete sin confirmación).
     */
    fun vetoPackage(context: Context, admin: ComponentName, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // 1. SUSPENDER: Impide que se ejecute incluso si pulsan "Abrir" en la Play Store
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                Log.w(TAG, "PAQUETE SUSPENDIDO POR EL SISTEMA: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suspendiendo $packageName", e)
        }

        // 2. OCULTAR: Desaparece del cajón de aplicaciones
        try {
            dpm.setApplicationHidden(admin, packageName, true)
            Log.w(TAG, "PAQUETE OCULTADO: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error ocultando $packageName", e)
        }

        // 3. DESINSTALACIÓN SILENCIOSA FORZADA (Device Owner no requiere confirmación del usuario)
        try {
            silentUninstall(context, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error desinstalando silenciosamente $packageName", e)
        }
    }

    private fun silentUninstall(context: Context, packageName: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val intent = Intent(context, PackageChangeReceiver::class.java).apply {
            action = "com.example.itsover02.ACTION_UNINSTALL_COMPLETE"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sender = PendingIntent.getBroadcast(context, 0, intent, flags).intentSender
        packageInstaller.uninstall(packageName, sender)
        Log.w(TAG, "ORDEN DE DESINSTALACIÓN SILENCIOSA EMITIDA PARA: $packageName")
    }
}