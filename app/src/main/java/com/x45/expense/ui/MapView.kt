package com.x45.expense.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.x45.expense.data.Txn
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/**
 * OpenStreetMap preview (no Google account or API key). Dark by inverting the tiles.
 * Static (no panning) because it sits inside scrolling screens; one gold dot per spend, bigger = more money.
 */
@Composable
fun SpendMap(spends: List<Txn>, modifier: Modifier = Modifier, zoom: Double = 13.0) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            setOnTouchListener { _, _ -> true }
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(map) { onDispose { map.onDetach() } }
    AndroidView({ map }, modifier) { m ->
        m.overlays.clear()
        val pts = spends.filter { it.lat != null && it.lng != null }
        if (pts.isEmpty()) return@AndroidView
        val max = pts.maxOf { it.amountPaise }.coerceAtLeast(1)
        pts.forEach { t ->
            val size = (14 + 22 * t.amountPaise / max).toInt()
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(t.lat!!, t.lng!!)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCCE6C88E.toInt()); setStroke(3, 0xFF0B0B0D.toInt())
                    val px = (size * ctx.resources.displayMetrics.density).toInt()
                    setSize(px, px)
                }
                infoWindow = null
            })
        }
        val lats = pts.map { it.lat!! }
        val lngs = pts.map { it.lng!! }
        if (lats.max() - lats.min() < 0.002 && lngs.max() - lngs.min() < 0.002) {
            m.controller.setZoom(zoom)
            m.controller.setCenter(GeoPoint(lats.average(), lngs.average()))
        } else {
            m.post {
                m.zoomToBoundingBox(BoundingBox(lats.max(), lngs.max(), lats.min(), lngs.min()).increaseByScale(1.4f), false)
            }
        }
        m.invalidate()
    }
}
