package com.example.itsover02

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object AppVetoManager {

    private const val TAG = "AppVetoManager"

    val BLOCKED_KEYWORDS = listOf("opera", "surfeasy", "tor browser")

    val GEOFENCE_APPS = setOf(
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.google.android.youtube",
        "com.google.ar.lens"
    )

    // Caché en memoria: guarda paquetes ya verificados como seguros para ahorrar 99% de CPU
    private val verifiedSafePackages = ConcurrentHashMap.newKeySet<String>()

    fun scanAndVetoAll(context: Context, admin: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        for (pkgInfo in packages) {
            val rawPkgName = pkgInfo.packageName
            // Si el paquete ya fue auditado antes como seguro, lo saltamos en 0 nanosegundos
            if (verifiedSafePackages.contains(rawPkgName) || rawPkgName == context.packageName) {
                continue
            }

            val pkgName = rawPkgName.lowercase()

            // 1. Comprobación rápida por nombre de paquete (sin tocar disco)
            var isForbidden = BLOCKED_KEYWORDS.any { keyword -> pkgName.contains(keyword) }

            // 2. Solo si no coincidió el paquete, leemos la etiqueta de la app una única vez en la vida
            if (!isForbidden) {
                val appInfo = pkgInfo.applicationInfo
                if (appInfo != null) {
                    val appName = try {
                        pm.getApplicationLabel(appInfo).toString().lowercase()
                    } catch (e: Exception) {
                        ""
                    }
                    isForbidden = BLOCKED_KEYWORDS.any { keyword -> appName.contains(keyword) }
                }
            }

            if (isForbidden) {
                Log.w(TAG, "OBJETIVO DETECTADO: $rawPkgName. VETANDO...")
                vetoPackage(context, admin, rawPkgName)
            } else {
                // Marcar como seguro en caché para no volver a gastar CPU en él
                verifiedSafePackages.add(rawPkgName)
            }
        }
    }

    fun setGeofenceAppsSuspended(context: Context, admin: ComponentName, suspend: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(admin, GEOFENCE_APPS.toTypedArray(), suspend)
                Log.i(TAG, "Suspensión de apps en geocerca ($suspend) aplicada.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error modificando suspensión de apps por geocerca", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun vetoPackage(context: Context, admin: ComponentName, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suspendiendo $packageName", e)
        }

        try {
            dpm.setApplicationHidden(admin, packageName, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error ocultando $packageName", e)
        }

        try {
            silentUninstall(context, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error en desinstalación silenciosa de $packageName", e)
        }
    }

    @SuppressLint("MissingPermission")
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
    }
}