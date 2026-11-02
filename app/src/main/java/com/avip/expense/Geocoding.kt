package com.avip.expense

import android.content.Context
import android.location.Geocoder
import android.location.Location
import java.util.Locale

data class PlaceInfo(val title: String, val address: String) {
    companion object {
        val EMPTY = PlaceInfo("", "")
    }
}

/** Reverse geocoding with Android's built-in, free Geocoder (no API key). */
object Geocoding {
    @Suppress("DEPRECATION")
    fun lookup(context: Context, loc: Location): PlaceInfo? {
        if (!Geocoder.isPresent()) return null
        return try {
            val a = Geocoder(context, Locale.getDefault())
                .getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull() ?: return null
            val title = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            val line = if (a.maxAddressLineIndex >= 0) a.getAddressLine(0) else null
            PlaceInfo(title, line ?: title)
        } catch (_: Exception) {
            null
        }
    }
}
