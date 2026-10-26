package com.example.itsover02

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.UserManager
import android.util.Log

object AppVetoManager {

    private const val TAG = "AppVetoManager"

    val BLOCKED_KEYWORDS = listOf("opera", "surfeasy", "tor browser")

    fun scanAndVetoAll(context: Context, admin: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        for (pkgInfo in packages) {
            val pkgName = pkgInfo.packageName.lowercase()

            // Validación segura de nulos para pkgInfo.applicationInfo
            val appInfo = pkgInfo.applicationInfo
            val appName = if (appInfo != null) {
                try {
                    pm.getApplicationLabel(appInfo).toString().lowercase()
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }

            val isForbidden = BLOCKED_KEYWORDS.any { keyword ->
                pkgName.contains(keyword) || appName.contains(keyword)
            }

            if (isForbidden && pkgName != context.packageName) {
                Log.w(TAG, "OBJETIVO DETECTADO EN POLLING: $pkgName ($appName). VETANDO...")
                vetoPackage(context, admin, pkgInfo.packageName)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun vetoPackage(context: Context, admin: ComponentName, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        // 1. Suspender
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suspendiendo $packageName", e)
        }

        // 2. Ocultar
        try {
            dpm.setApplicationHidden(admin, packageName, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error ocultando $packageName", e)
        }

        // 3. Desinstalación silenciosa
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
        Log.w(TAG, "Orden de desinstalación desatendida ejecutada para: $packageName")
    }
}