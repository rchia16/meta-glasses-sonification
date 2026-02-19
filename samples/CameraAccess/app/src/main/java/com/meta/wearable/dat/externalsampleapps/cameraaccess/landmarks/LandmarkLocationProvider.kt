package com.meta.wearable.dat.externalsampleapps.cameraaccess.landmarks

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LandmarkLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)

class LandmarkLocationProvider(
    private val context: Context,
) {
    private val locationManager: LocationManager =
        context.getSystemService(LocationManager::class.java)

    suspend fun currentLocation(): LandmarkLocation? {
        if (!hasLocationPermission()) return null
        val gps = requestCurrentLocation(LocationManager.GPS_PROVIDER)
        if (gps != null) return gps
        val network = requestCurrentLocation(LocationManager.NETWORK_PROVIDER)
        if (network != null) return network
        return lastKnownLocation()
    }

    private fun hasLocationPermission(): Boolean {
        val coarseGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val fineGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return coarseGranted || fineGranted
    }

    private suspend fun requestCurrentLocation(provider: String): LandmarkLocation? {
        if (!locationManager.isProviderEnabled(provider)) return null
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                    continuation.resume(location?.toLandmarkLocation())
                }
            }.onFailure {
                continuation.resume(null)
            }
        }
    }

    private fun lastKnownLocation(): LandmarkLocation? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            if (location != null) {
                return location.toLandmarkLocation()
            }
        }
        return null
    }

    private fun Location.toLandmarkLocation(): LandmarkLocation {
        return LandmarkLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
        )
    }
}

