package com.example.itsover02

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        enforceHardening(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        enforceHardening(context)
    }

    companion object {
        private const val TAG = "DeviceAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, com.example.itsover02.DeviceAdminReceiver::class.java)
        }

        fun enforceHardening(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val adminComponent = getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    // 1. Always-on VPN sin bloqueo ciego
                    dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)

                    // 2. BLOQUEO DE DESINSTALACIÓN: Tu app + AppBlock
                    dpm.setUninstallBlocked(adminComponent, context.packageName, true)
                    dpm.setUninstallBlocked(adminComponent, "cz.mobilesoft.appblock", true) // <--- ¡AQUÍ BLINDAS APPBLOCK!

                    // 3. Blindaje de DNS Privado
                    dpm.setGlobalSetting(adminComponent, "private_dns_mode", "off")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                    }

                    // 4. Bloqueo de reseteo de red
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET)

                    // 5. Políticas administradas para navegadores
                    MdmPolicyManager.applyBrowserRestrictions(context, adminComponent)

                    // 6. Restricciones contra APKs desconocidos
                    AppRestrictionManager.applyAllRestrictions(context, adminComponent)

                    // 7. Inmunización de Hardware de Ubicación y GPS (ADB permanece activo)
                    LocationSecurityManager.enforceLocationAlwaysOn(context, adminComponent)

                    Log.i(TAG, "¡ÉXITO TOTAL! Políticas de Device Owner aplicadas sin errores.")

                } catch (e: Exception) {
                    Log.e(TAG, "Error aplicando directivas de Device Owner", e)
                }
            } else {
                Log.w(TAG, "La aplicación aún no es reconocida como Device Owner.")
            }
        }
    }
}