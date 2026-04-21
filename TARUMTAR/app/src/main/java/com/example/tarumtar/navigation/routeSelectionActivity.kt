package com.example.tarumtar.navigation

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

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
        val polyline: Polyline,
        val distanceMeters: Double
    )

    private val routeOptions = mutableListOf<RouteOption>()
    private val routeLines = mutableListOf<Polyline>()

    private var selectedRouteIndex: Int = 0
    private var latestPath: List<Node> = emptyList()

    // Route button references
    private lateinit var layoutRouteSelection: LinearLayout
    private lateinit var btnPath1: Button
    private lateinit var btnPath2: Button
    private lateinit var btnPath3: Button
    private lateinit var btnStartAR: Button

    // Weather view references
    private lateinit var layoutWeather: LinearLayout
    private lateinit var tvTemp: TextView
    private lateinit var tvWeatherDesc: TextView
    private lateinit var tvRainfall: TextView

    private val pathButtons get() = listOf(btnPath1, btnPath2, btnPath3)

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val WEATHER_API_KEY = "016329090d10da4b9fd4082a3d2b0372"
    
    // Store current severe weather status (rain or extreme heat)
    private var isSevereWeather = false

    private fun norm(s: String): String = s.trim().uppercase()

    // Walking speed ~1.4 m/s → ~84 m/min
    private fun distanceToMinutes(meters: Double): Int = ((meters / 84.0) + 0.5).toInt().coerceAtLeast(1)

    // Calculate total distance for a path using haversine
    private fun calcPathDistance(path: List<Node>): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += haversineMeters(path[i].lat, path[i].lng, path[i + 1].lat, path[i + 1].lng)
        }
        return total
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.route_selection)

        setupTitle()

        map = findViewById(R.id.osmMap)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)

        layoutRouteSelection = findViewById(R.id.layoutRouteSelection)
        btnPath1 = findViewById(R.id.btnPath1)
        btnPath2 = findViewById(R.id.btnPath2)
        btnPath3 = findViewById(R.id.btnPath3)
        btnStartAR = findViewById(R.id.btnStartAR)

        layoutWeather = findViewById(R.id.layoutWeather)
        tvTemp = findViewById(R.id.tvTemp)
        tvWeatherDesc = findViewById(R.id.tvWeatherDesc)
        tvRainfall = findViewById(R.id.tvRainfall)

        val etStart = findViewById<AutoCompleteTextView>(R.id.etStart)
        val etEnd   = findViewById<AutoCompleteTextView>(R.id.etEnd)
        val btnShow = findViewById<Button>(R.id.btnShowPaths)

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

                    // Fetch Weather for this location
                    fetchWeatherData(nodes.first().lat, nodes.first().lng)

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

                        drawPathsAndShowButtons(s.id, e.id)
                    }

                    btnStartAR.setOnClickListener {
                        if (latestPath.isEmpty()) {
                            Toast.makeText(
                                this,
                                "Select a route first before starting AR.",
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

    // ---------------- TITLE ----------------

    private fun setupTitle() {
        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        // "TAR UMT AR" — TAR=red, UMT=blue, AR=black
        val text = SpannableString("TAR UMT AR")
        text.setSpan(
            ForegroundColorSpan(Color.parseColor("#E30613")),
            0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            ForegroundColorSpan(Color.parseColor("#1E40FF")),
            4, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            ForegroundColorSpan(Color.BLACK),
            8, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        txtTitle.text = text
    }

    // ---------------- WEATHER ----------------

    private fun fetchWeatherData(lat: Double, lng: Double) {
        scope.launch {
            try {
                val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lng&appid=$WEATHER_API_KEY&units=metric"
                val response = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().body?.string()
                }

                if (response != null) {
                    val json = JSONObject(response)
                    val mainObj = json.getJSONObject("main")
                    val weatherArr = json.getJSONArray("weather")
                    val rainObj = json.optJSONObject("rain")

                    val temp = mainObj.getDouble("temp")
                    val description = weatherArr.getJSONObject(0).getString("main")
                    val rainVolume = rainObj?.optDouble("1h", 0.0) ?: 0.0
                    
                    // Determine if it's severe weather (rain or extreme heat over 33C)
                    val isRaining = rainVolume > 0 || description.lowercase().contains("rain")
                    val isHot = temp > 33.0
                    isSevereWeather = isRaining || isHot

                    layoutWeather.visibility = View.VISIBLE
                    tvTemp.text = "${temp.toInt()}°C"
                    tvWeatherDesc.text = description

                    if (rainVolume > 0) {
                        tvRainfall.visibility = View.VISIBLE
                        tvRainfall.text = "Rain: ${String.format("%.1f", rainVolume)} mm/h"
                    } else {
                        tvRainfall.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // Fail silently or handle error log
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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

    private fun isPathSheltered(path: List<Node>): Boolean {
        for (i in 0 until path.size - 1) {
            val fromId = norm(path[i].id)
            val toId = norm(path[i + 1].id)
            val edge = edges.firstOrNull {
                (norm(it.From) == fromId && norm(it.To) == toId) ||
                (it.bidirectional && norm(it.To) == fromId && norm(it.From) == toId)
            }
            if (edge != null && !edge.isSheltered) {
                return false
            }
        }
        return true
    }

    private fun drawPathsAndShowButtons(startId: String, endId: String) {
        clearRoutesOnly()

        val paths = pathFinder.findTop3Paths(nodes, edges, startId, endId, isSevereWeather)

        if (paths.isEmpty()) {
            Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show()
            layoutRouteSelection.visibility = View.GONE
            return
        }

        // Draw all paths on map
        paths.forEachIndexed { index, path ->
            drawRouteLine(path, index)
        }

        // Populate route buttons
        layoutRouteSelection.visibility = View.VISIBLE

        // Hide all buttons first
        pathButtons.forEach { it.visibility = View.GONE }

        val maxDistance = routeOptions.maxOfOrNull { it.distanceMeters } ?: 0.0

        routeOptions.forEachIndexed { index, option ->
            val btn = pathButtons[index]
            val distM = option.distanceMeters
            val mins = distanceToMinutes(distM)
            val distDisplay = if (distM >= 1000) "%.1fkm".format(distM / 1000) else "${distM.toInt()}m"

            val isNotRecommended = if (isSevereWeather) {
                // If severe weather, not recommended if it has unsheltered segments
                !isPathSheltered(option.path)
            } else {
                // Normal weather: longest distance is not recommended
                option.distanceMeters == maxDistance && routeOptions.size > 1
            }

            val suffix = if (isNotRecommended) " (not recommend)" else ""

            btn.text = "Path ${index + 1}: ${mins}min (${distDisplay})${suffix}"
            btn.visibility = View.VISIBLE

            btn.setOnClickListener {
                selectRoute(index)
            }
        }

        // Auto-select first route
        selectRoute(0)

        val msg = when (paths.size) {
            1 -> "1 route found."
            2 -> "2 routes found. Select a path."
            else -> "3 routes found. Select a path."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        map.invalidate()
    }

    private fun selectRoute(index: Int) {
        if (index >= routeOptions.size) return

        selectedRouteIndex = index
        latestPath = routeOptions[index].path

        // Update button highlights
        routeOptions.forEachIndexed { i, _ ->
            val btn = pathButtons[i]
            if (i == index) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#0288D1") // darker blue for selected
                )
                btn.setTextColor(Color.WHITE)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#81D4FA") // light blue for unselected
                )
                btn.setTextColor(Color.BLACK)
            }
        }

        // Highlight the selected polyline on the map
        highlightSelectedPolyline(routeOptions[index].polyline)
        zoomToPath(routeOptions[index].path)
    }

    private fun drawRouteLine(path: List<Node>, routeIndex: Int) {
        val color = getRouteColor(routeIndex)
        val distanceMeters = calcPathDistance(path)

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
            polyline = line,
            distanceMeters = distanceMeters
        )

        routeOptions.add(option)
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

        val minLat = geoPoints.minOf { it.latitude }
        val maxLat = geoPoints.maxOf { it.latitude }
        val minLon = geoPoints.minOf { it.longitude }
        val maxLon = geoPoints.maxOf { it.longitude }

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
        layoutRouteSelection.visibility = View.GONE
        pathButtons.forEach { it.visibility = View.GONE }
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