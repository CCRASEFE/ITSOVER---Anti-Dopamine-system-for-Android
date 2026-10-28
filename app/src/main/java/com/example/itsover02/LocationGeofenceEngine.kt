package com.example.itsover02

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log

data class RestrictedZone(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float
)

object LocationGeofenceEngine {

    private const val TAG = "GeofenceEngine"

    // Geocercas oficiales configuradas para Perú (Trujillo y Pacasmayo)
    private val RESTRICTED_ZONES = listOf(
        RestrictedZone(
            name = "Zona 1 - Trujillo",
            latitude = -8.110955,
            longitude = -79.039388,
            radiusMeters = 250f
        ),
        RestrictedZone(
            name = "Zona 2 - Pacasmayo",
            latitude = -7.395254,
            longitude = -79.555194,
            radiusMeters = 500f
        )
    )

    // Parámetros optimizados para bajo consumo de batería (30s / 15 metros de movimiento)
    private const val UPDATE_INTERVAL_MS = 30_000L
    private const val MIN_DISTANCE_METERS = 15f

    private var appContext: Context? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var wasInside: Boolean? = null

    @SuppressLint("MissingPermission")
    fun startMonitoring(context: Context) {
        if (locationManager != null) return

        appContext = context.applicationContext
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                evaluateGeofence(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val listener = locationListener ?: return

        // 1. Evaluar en 0ms la última ubicación en caché al encender el servicio
        val lastFused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER) } catch (e: Exception) { null }
        } else null
        val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }

        val bestLast = lastFused ?: lastNet ?: lastGps
        if (bestLast != null) {
            evaluateGeofence(bestLast)
        }

        // 2. Suscripción eficiente ahorradora de batería
        try {
            // Proveedor Pasivo: 0% consumo extra de batería (aprovecha lecturas de otras apps del sistema)
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    5000L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }

            // Si tiene Android 12+, usamos FUSED_PROVIDER (combina Wi-Fi/Celdas con mínimo uso de batería)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    listener,
                    Looper.getMainLooper()
                )
            } else {
                // Fallback para versiones anteriores
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper()
                    )
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS * 2, // 60s para GPS satelital puro
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper()
                    )
                }
            }

            Log.i(TAG, "Monitoreo de geocercas iniciado en modo Eco-Battery.")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando LocationManager", e)
        }
    }

    private fun evaluateGeofence(location: Location) {
        var isInsideAny = false
        val results = FloatArray(1)

        for (zone in RESTRICTED_ZONES) {
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                zone.latitude,
                zone.longitude,
                results
            )
            if (results[0] <= zone.radiusMeters) {
                isInsideAny = true
                break
            }
        }

        // Solo ejecuta llamadas al sistema si el usuario realmente cruzó la frontera (entró o salió)
        if (wasInside != isInsideAny) {
            wasInside = isInsideAny
            BlocklistRepository.isGeofenceRestricted = isInsideAny

            appContext?.let { ctx ->
                val admin = DeviceAdminReceiver.getComponentName(ctx)
                Log.w(TAG, "TRANSICIÓN DE FRONTERA: Suspendiendo apps = $isInsideAny")
                AppVetoManager.setGeofenceAppsSuspended(ctx, admin, isInsideAny)
            }
        }
    }

    fun stopMonitoring() {
        locationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error deteniendo LocationManager", e)
            }
        }
        locationManager = null
        locationListener = null
        appContext = null
        wasInside = null
    }
}