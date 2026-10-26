package com.example.itsover02

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Callback de desinstalación silenciosa completada
        if (action == "com.example.itsover02.ACTION_UNINSTALL_COMPLETE") {
            Log.i(TAG, "Desinstalación silenciosa completada con éxito.")
            return
        }

        val data = intent.data ?: return
        val packageName = data.schemeSpecificPart ?: return

        Log.d(TAG, "Broadcast de paquete detectado: $action para $packageName")

        if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED) {
            if (AppRestrictionManager.EVASIVE_PACKAGES.contains(packageName)) {
                Log.w(TAG, "¡INTRUSO DETECTADO! VETANDO PAQUETE AL INSTANTE: $packageName")
                val adminComponent = DeviceAdminReceiver.getComponentName(context)
                AppRestrictionManager.vetoPackage(context, adminComponent, packageName)
            }
        }
    }
}