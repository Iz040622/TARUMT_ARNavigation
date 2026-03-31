package com.example.tarumtar.navigation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.R
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class routeSelectionActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val repo = graphRepository()

    private var nodes: List<Node> = emptyList()
    private var edges: List<Edge> = emptyList()

    private var startNode: Node? = null
    private var endNode: Node? = null

    private var startMarker: Marker? = null
    private var endMarker: Marker? = null

    private data class RouteOption(
        val index: Int,
        val path: List<Node>,
        val polyline: Polyline
    )

    private val routeOptions = mutableListOf<RouteOption>()
    private val routeLines = mutableListOf<Polyline>()

    private var latestPath: List<Node> = emptyList()

    private fun norm(s: String): String = s.trim().uppercase()

    private fun openArForRoute(route: List<Node>) {
        val intent = Intent(this, ARNavigationActivity::class.java)
        intent.putExtra("route_lats", route.map { it.lat }.toDoubleArray())
        intent.putExtra("route_lngs", route.map { it.lng }.toDoubleArray())
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.route_selection)

        map = findViewById(R.id.osmMap)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)

        val etStart = findViewById<AutoCompleteTextView>(R.id.etStart)
        val etEnd = findViewById<AutoCompleteTextView>(R.id.etEnd)
        val btnShow = findViewById<Button>(R.id.btnShowPaths)
        val btnStartAR = findViewById<Button>(R.id.btnStartAR)

        Toast.makeText(this, "Loading graph from Firebase...", Toast.LENGTH_SHORT).show()

        repo.loadGraph(
            onDone = { loadedNodes, loadedEdges ->
                nodes = loadedNodes
                edges = loadedEdges

                runOnUiThread {
                    if (nodes.isEmpty()) {
                        Toast.makeText(this, "No nodes found in Firebase!", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    val center = GeoPoint(nodes.first().lat, nodes.first().lng)
                    map.controller.setCenter(center)

                    val visibleNodes = nodes.filter { it.visible }
                    val labels = visibleNodes.map { it.label() }
                    val adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_dropdown_item_1line,
                        labels
                    )

                    etStart.setAdapter(adapter)
                    etEnd.setAdapter(adapter)

                    etStart.setOnItemClickListener { _, _, position, _ ->
                        val picked = adapter.getItem(position) ?: return@setOnItemClickListener
                        val id = picked.substringBefore(" - ").trim()
                        startNode = visibleNodes.firstOrNull { norm(it.id) == norm(id) }

                        startNode?.let { placeStartMarker(it) }
                        clearRoutesOnly()
                    }

                    etEnd.setOnItemClickListener { _, _, position, _ ->
                        val picked = adapter.getItem(position) ?: return@setOnItemClickListener
                        val id = picked.substringBefore(" - ").trim()
                        endNode = visibleNodes.firstOrNull { norm(it.id) == norm(id) }

                        endNode?.let { placeEndMarker(it) }
                        clearRoutesOnly()
                    }

                    btnShow.setOnClickListener {
                        val s = startNode
                        val e = endNode

                        if (s == null || e == null) {
                            Toast.makeText(this, "Select start and end first.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        drawPathsAndEnableSelection(s.id, e.id)
                    }

                    btnStartAR.setOnClickListener {
                        if (latestPath.isEmpty()) {
                            Toast.makeText(
                                this,
                                "Tap a route first before starting AR.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }

                        val start = latestPath.first()

                        val intent = Intent(this, ARNavigationActivity::class.java)
                        intent.putExtra("route_lats", latestPath.map { it.lat }.toDoubleArray())
                        intent.putExtra("route_lngs", latestPath.map { it.lng }.toDoubleArray())

                        intent.putExtra("start_id", start.id)
                        intent.putExtra("start_lat", start.lat)
                        intent.putExtra("start_lng", start.lng)

                        startActivity(intent)
                    }

                    Toast.makeText(this, "Graph loaded! Select start and end.", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { e ->
                runOnUiThread {
                    Toast.makeText(this, "Firebase load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // ---------------- MARKERS ----------------

    private fun placeStartMarker(n: Node) {
        startMarker?.let { map.overlays.remove(it) }

        startMarker = Marker(map).apply {
            position = GeoPoint(n.lat, n.lng)
            title = "START: ${n.label()}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        map.overlays.add(startMarker)
        map.controller.animateTo(startMarker!!.position)
        map.invalidate()
    }

    private fun placeEndMarker(n: Node) {
        endMarker?.let { map.overlays.remove(it) }

        endMarker = Marker(map).apply {
            position = GeoPoint(n.lat, n.lng)
            title = "END: ${n.label()}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        map.overlays.add(endMarker)
        map.controller.animateTo(endMarker!!.position)
        map.invalidate()
    }

    // ---------------- ROUTES ----------------

    private fun drawPathsAndEnableSelection(startId: String, endId: String) {
        clearRoutesOnly()

        val paths = pathFinder.findTop3Paths(nodes, edges, startId, endId)

        if (paths.isEmpty()) {
            Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show()
            return
        }

        paths.forEachIndexed { index, path ->
            drawRouteClickable(path, index)
        }

        latestPath = paths.first()
        routeOptions.firstOrNull()?.let { first ->
            highlightSelectedPolyline(first.polyline)
            zoomToPath(first.path)
        }

        val msg = when (paths.size) {
            1 -> "Only 1 route found."
            2 -> "2 routes found. Tap a route to select."
            else -> "3 routes found. Tap a route to select."
        }

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        map.invalidate()
    }

    private fun drawRouteClickable(path: List<Node>, routeIndex: Int) {
        val color = getRouteColor(routeIndex)

        val line = Polyline().apply {
            setPoints(path.map { GeoPoint(it.lat, it.lng) })
            width = 10f
            outlinePaint.color = color
            outlinePaint.strokeWidth = 10f
            isGeodesic = true
            title = "Route ${routeIndex + 1}"
        }

        val option = RouteOption(
            index = routeIndex,
            path = path,
            polyline = line
        )

        routeOptions.add(option)

        line.setOnClickListener { polyline, _, _ ->
            val selectedOption = routeOptions.firstOrNull { it.polyline == polyline }
            if (selectedOption != null) {
                latestPath = selectedOption.path
                highlightSelectedPolyline(polyline)
                zoomToPath(selectedOption.path)

                Toast.makeText(
                    this,
                    "Route ${selectedOption.index + 1} selected ✅",
                    Toast.LENGTH_SHORT
                ).show()

                map.invalidate()
            }
            true
        }

        map.overlays.add(line)
        routeLines.add(line)
    }

    private fun highlightSelectedPolyline(selected: Polyline?) {
        routeOptions.forEach { option ->
            val normalColor = getRouteColor(option.index)
            option.polyline.outlinePaint.color = normalColor
            option.polyline.width = 10f
            option.polyline.outlinePaint.strokeWidth = 10f
        }

        selected?.let {
            it.width = 18f
            it.outlinePaint.strokeWidth = 18f
        }

        map.invalidate()
    }

    private fun getRouteColor(index: Int): Int {
        return when (index) {
            0 -> Color.BLUE
            1 -> Color.parseColor("#FF9800") // orange
            2 -> Color.GREEN
            else -> Color.MAGENTA
        }
    }

    private fun zoomToPath(path: List<Node>) {
        if (path.isEmpty()) return

        val geoPoints = path.map { GeoPoint(it.lat, it.lng) }

        if (geoPoints.size == 1) {
            map.controller.animateTo(geoPoints.first())
            return
        }

        var minLat = geoPoints.minOf { it.latitude }
        var maxLat = geoPoints.maxOf { it.latitude }
        var minLon = geoPoints.minOf { it.longitude }
        var maxLon = geoPoints.maxOf { it.longitude }

        val paddingLat = 0.0003
        val paddingLon = 0.0003

        val box = BoundingBox(
            maxLat + paddingLat,
            maxLon + paddingLon,
            minLat - paddingLat,
            minLon - paddingLon
        )

        map.zoomToBoundingBox(box, true, 120)
    }

    private fun clearRoutesOnly() {
        routeLines.forEach { map.overlays.remove(it) }
        routeLines.clear()
        routeOptions.clear()
        latestPath = emptyList()
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}