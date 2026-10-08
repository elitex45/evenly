package com.x45.expense.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Phone location at the moment a spend arrives. Uses plain Android LocationManager (no Google Play services). */
object Places {

    /** Blocking; call off the main thread. Returns (lat, lng) or null. */
    fun current(ctx: Context, timeoutSec: Long): Pair<Double, Double>? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)

        // Ask every provider at once (high accuracy) and take the first answer.
        var fresh: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val wanted = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }
            val latch = CountDownLatch(1)
            val cancels = wanted.map { android.os.CancellationSignal() }
            wanted.forEachIndexed { i, provider ->
                try {
                    val onLoc = java.util.function.Consumer<Location?> { loc -> if (loc != null && fresh == null) { fresh = loc; latch.countDown() } }
                    if (Build.VERSION.SDK_INT >= 31) {
                        val req = android.location.LocationRequest.Builder(0L)
                            .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setDurationMillis(timeoutSec * 1000).build()
                        lm.getCurrentLocation(provider, req, cancels[i], ctx.mainExecutor, onLoc)
                    } else {
                        lm.getCurrentLocation(provider, cancels[i], ctx.mainExecutor, onLoc)
                    }
                } catch (_: SecurityException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            if (wanted.isNotEmpty()) latch.await(timeoutSec, TimeUnit.SECONDS)
            cancels.forEach { it.cancel() }
        }
        // Fallback: a recent fix (last 30 min) from any provider.
        val best = fresh ?: providers.mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null }
        }.filter { System.currentTimeMillis() - it.time < 30 * 60_000L }.maxByOrNull { it.time }
        return best?.let { it.latitude to it.longitude }
    }

    /** Adds place names to spends that have coordinates but no name yet. Blocking; needs network. */
    fun fillNames(ctx: Context) {
        val db = Db.get(ctx)
        db.missingPlaceNames().forEach { t ->
            val n = name(ctx, t.lat ?: return@forEach, t.lng ?: return@forEach) ?: return@forEach
            db.setLocation(t.id, t.lat, t.lng, n)
        }
    }

    /** Short place name like "Indiranagar, Bengaluru", or null when offline. */
    @Suppress("DEPRECATION")
    fun name(ctx: Context, lat: Double, lng: Double): String? = try {
        Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.subLocality ?: a.thoroughfare, a.locality).distinct().joinToString(", ").ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }
}
