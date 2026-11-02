package com.taptrack.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@SuppressLint("MissingPermission")
fun Context.locationFlow(): Flow<Location> = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(this@locationFlow)

    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
        .setMinUpdateIntervalMillis(1000L)
        .build()

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { trySend(it) }
        }
    }

    client.requestLocationUpdates(request, callback, Looper.getMainLooper())

    awaitClose { client.removeLocationUpdates(callback) }
}

@SuppressLint("MissingPermission")
suspend fun Context.getLastKnownLocation(): Location? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation
            .addOnSuccessListener { loc -> cont.resumeWith(Result.success(loc)) }
            .addOnFailureListener { cont.resumeWith(Result.success(null)) }
    }

fun formatCoordinates(lat: Double, lng: Double): String =
    "%.6f, %.6f".format(lat, lng)
