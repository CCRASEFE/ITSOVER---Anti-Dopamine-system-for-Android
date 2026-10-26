package com.example.itsover02

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

class EmergencyUninstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.itsover02.EMERGENCY_UNLOCK") {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComponent = DeviceAdminReceiver.getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    // 1. Quitar el candado de desinstalación
                    dpm.setUninstallBlocked(adminComponent, context.packageName, false)

                    // 2. Restaurar restricciones de DNS y red
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                    }
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET)

                    // Restauramos el DNS a automático ("opportunistic")
                    dpm.setGlobalSetting(adminComponent, "private_dns_mode", "opportunistic")

                    // 3. Quitar Always-On VPN
                    dpm.setAlwaysOnVpnPackage(adminComponent, null, false)

                    // 4. Renunciar al rol de Device Owner
                    dpm.clearDeviceOwnerApp(context.packageName)

                    // Dentro del try {} de EmergencyUninstallReceiver:
                    dpm.setUninstallBlocked(adminComponent, context.packageName, false)
                    dpm.setUninstallBlocked(adminComponent, "cz.mobilesoft.appblock", false) // <--- Libera AppBlock en caso de emergencia

                    Log.w("EmergencyReceiver", "¡EMERGENCIA EJECUTADA! Candados retirados. La app ya puede desinstalarse.")
                } catch (e: Exception) {
                    Log.e("EmergencyReceiver", "Error en desbloqueo de emergencia", e)
                }
            }
        }
    }
}