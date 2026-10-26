package com.example.itsover02

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Broadcast recibido en boot: $action")

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            // Contexto seguro para Direct Boot (almacenamiento no cifrado por credencial de usuario)
            val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }

            // 1. Reafirmar directivas de Device Owner
            DeviceAdminReceiver.enforceHardening(directBootContext)

            // 2. Levantar el Foreground Service con captura defensiva de excepciones de Android 14
            val serviceIntent = Intent(directBootContext, LocalDnsBlockerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(directBootContext, serviceIntent)
                } else {
                    directBootContext.startService(serviceIntent)
                }
                Log.i(TAG, "Solicitud de inicio de LocalDnsBlockerService enviada con éxito.")
            } catch (e: Exception) {
                // Previene crash si el sistema operativo está en un estado de transición estricto
                Log.e(TAG, "Fallo al solicitar startForegroundService en boot: ${e.message}")
            }
        }
    }
}