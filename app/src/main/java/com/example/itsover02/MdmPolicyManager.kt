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

    // Paquetes del ecosistema Brave Browser
    private val BRAVE_PACKAGES = arrayOf(
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly"
    )

    // Paquetes de Opera
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
        applyBraveRestrictions(dpm, adminComponent)
    }

    private fun applyChromeRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            val bundle = Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                putInt("IncognitoModeAvailability", 1)
                putBoolean("ForceGoogleSafeSearch", true)
                putInt("ForceYouTubeRestrict", 2)
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
                    putBoolean("VPNEnabled", false)
                    putString("VPNEnabled", "false")
                    putString("ProxyMode", "direct")
                    putString("DnsOverHttpsMode", "off")
                    putInt("IncognitoModeAvailability", 1)
                }
                dpm.setApplicationRestrictions(admin, pkg, bundle)
                Log.i(TAG, "Políticas aplicadas a $pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Error inyectando políticas a $pkg", e)
            }
        }
    }

    private fun applyBraveRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        for (pkg in BRAVE_PACKAGES) {
            try {
                val bundle = Bundle().apply {
                    // 1. Forzar SafeSearch estricto en Google
                    putBoolean("ForceGoogleSafeSearch", true)

                    // 2. Forzar modo restringido estricto en YouTube (2 = Strict, 1 = Moderate, 0 = Off)
                    putInt("ForceYouTubeRestrict", 2)

                    // 3. Desactivar DNS sobre HTTPS (DoH) para forzar el uso del túnel VPN puerto 53
                    putString("DnsOverHttpsMode", "off")

                    // 4. Desactivar modo incógnito/privado
                    putInt("IncognitoModeAvailability", 1)
                }
                dpm.setApplicationRestrictions(admin, pkg, bundle)
                Log.i(TAG, "Políticas MDM aplicadas con éxito a Brave Browser ($pkg).")
            } catch (e: Exception) {
                Log.e(TAG, "Error inyectando políticas a $pkg", e)
            }
        }
    }
}