package com.stsmn.monitor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

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

fun openGoogleEarth(ctx: Context, lat: Double, lng: Double) {
    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return
    val ll = String.format(Locale.US, "%.6f,%.6f", lat, lng)
    val earthWebUrl = "https://earth.google.com/web/@$ll,350a,1200d,35y,0h,45t,0r"

    val earthAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse(earthWebUrl)).apply {
        setPackage("com.google.earth")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val mapsSatelliteIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:$ll?z=19&q=$ll(STSMN%20Transformer)"),
    ).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(earthWebUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        ctx.startActivity(earthAppIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(mapsSatelliteIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(browserIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, "No app available to open Google Earth", Toast.LENGTH_LONG).show()
            }
        }
    }
}
