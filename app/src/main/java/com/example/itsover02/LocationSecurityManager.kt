package com.example.itsover02

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

object LocationSecurityManager {

    private const val TAG = "LocationSecurityMgr"

    /**
     * Inmuniza la ubicación en el dispositivo:
     * 1. Elimina DISALLOW_SHARE_LOCATION (que forzaba el GPS a apagado).
     * 2. Enciende el hardware GPS con setLocationEnabled(true).
     * 3. Aplica DISALLOW_CONFIG_LOCATION para congelar el GPS en estado ENCENDIDO.
     * MANTIENE ADB ACTIVO (sin restricciones de depuración).
     */
    fun enforceLocationAlwaysOn(context: Context, adminComponent: ComponentName) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            // 1. REGLA DE ORO DE AOSP: Quitar la restricción que apagaba el GPS por defecto
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION)

            // 2. Encender el hardware GPS de forma obligatoria a nivel de sistema (Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(adminComponent, true)
                Log.i(TAG, "Hardware GPS forzado a ENCENDIDO (setLocationEnabled = true).")
            }

            // 3. Congelar el GPS en estado ENCENDIDO (el usuario no puede apagarlo en Ajustes ni en el Panel)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)

            // 4. Auto-concesión silenciosa de permisos de ubicación para la app
            grantLocationPermissionsSilently(dpm, adminComponent, context.packageName)

            Log.i(TAG, "Ubicación forzada y bloqueada en estado ENCENDIDO con éxito.")
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando inmunización de ubicación", e)
        }
    }

    private fun grantLocationPermissionsSilently(dpm: DevicePolicyManager, admin: ComponentName, packageName: String) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        for (perm in permissions) {
            try {
                dpm.setPermissionGrantState(admin, packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo auto-conceder $perm: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                dpm.setPermissionGrantState(admin, packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo auto-conceder BACKGROUND_LOCATION: ${e.message}")
            }
        }
    }
}