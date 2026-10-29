package nl.beandesk.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import nl.beandesk.core.model.LatLng

/**
 * The user's location, only while someone collects it (the screen is visible).
 * It stays on the phone: the app filters and sorts places locally.
 */
class LocationRepository(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    /** Callers check the permission first; without it the flow simply ends. */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<LatLng> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(LatLng(it.latitude, it.longitude)) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()
        try {
            // The last known fix is instant, so the list can sort before the first new fix arrives.
            client.lastLocation.addOnSuccessListener { location ->
                location?.let { trySend(LatLng(it.latitude, it.longitude)) }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 15_000L
        const val MIN_DISTANCE_METERS = 10f
    }
}
