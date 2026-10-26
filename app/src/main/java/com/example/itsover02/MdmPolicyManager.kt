package com.example.itsover02

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log

object MdmPolicyManager {

    private const val TAG = "MdmPolicyManager"

    private const val PKG_FIREFOX = "org.mozilla.firefox"
    private const val PKG_CHROME = "com.android.chrome"

    // Paquetes de Opera para Android
    private val OPERA_PACKAGES = arrayOf(
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.browser.beta",
        "com.opera.touch"
    )

    fun applyBrowserRestrictions(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "No se pueden aplicar restricciones: la aplicación no es Device Owner.")
            return
        }

        applyChromeRestrictions(dpm, adminComponent)
        applyFirefoxRestrictions(dpm, adminComponent)
        applyOperaRestrictions(dpm, adminComponent)
    }

    private fun applyChromeRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            val bundle = Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                putInt("IncognitoModeAvailability", 1)
            }
            dpm.setApplicationRestrictions(admin, PKG_CHROME, bundle)
            Log.i(TAG, "Políticas aplicadas a Chrome.")
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando restricciones a Chrome", e)
        }
    }

    private fun applyFirefoxRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            val bundle = Bundle().apply {
                putInt("network.trr.mode", 5)
                putBoolean("InstallAddonsPermission", false)
                putBoolean("PrivateBrowsingShortcut", false)
                putBoolean("DisablePrivateBrowsing", true)
            }
            dpm.setApplicationRestrictions(admin, PKG_FIREFOX, bundle)
            Log.i(TAG, "Políticas aplicadas a Firefox.")
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando restricciones a Firefox", e)
        }
    }

    private fun applyOperaRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        for (pkg in OPERA_PACKAGES) {
            try {
                val bundle = Bundle().apply {
                    // Desactivación de VPN y proxies propietarios
                    putBoolean("VPNEnabled", false)
                    putString("VPNEnabled", "false")

                    // Directiva Chromium base: Forzar conexión directa sin proxies
                    putString("ProxyMode", "direct")
                    putString("DnsOverHttpsMode", "off")

                    // Desactivar pestañas privadas
                    putInt("IncognitoModeAvailability", 1)
                }

                dpm.setApplicationRestrictions(admin, pkg, bundle)
                Log.i(TAG, "Políticas aplicadas exitosamente a $pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Error inyectando políticas a $pkg", e)
            }
        }
    }
}