package com.stsmn.monitor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/**
 * Opens real Google Street View at the transformer's position. No API key and no payment: it hands over to the
 * Google Maps app (or the browser if the app is missing). Coordinates are validated numbers, never free text.
 */
fun openStreetView(ctx: Context, lat: Double, lng: Double) {
    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
    val ll = String.format(Locale.US, "%.6f,%.6f", lat, lng)
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$ll")).setPackage("com.google.android.apps.maps")
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$ll"))
    try {
        ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app available to open Street View", Toast.LENGTH_LONG).show()
        }
    }
}
