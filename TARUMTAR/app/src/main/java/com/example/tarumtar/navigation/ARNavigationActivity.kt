package com.example.tarumtar.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tarumtar.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.ArCoreApk
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import kotlin.math.max
import kotlin.math.min

class ARNavigationActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2001

        private const val POSITION_DETECT_DELAY_MS = 100L
        private const val VISIBLE_AHEAD_COUNT = 6

        private const val VISIBLE_BEHIND_COUNT = 1

        private const val ARROW_SPACING_METERS = 1.2f
        private const val MODEL_YAW_OFFSET_DEG = 90f
        private const val ROUTE_Y_LIFT = 0.03f

        private const val AUTO_PLACE_Y_RATIO = 0.72f
        private const val STABLE_FLOOR_FRAMES_REQUIRED = 8

        // user must be within this distance from selected start node
        private const val START_RADIUS_METERS = 12f
    }

    private lateinit var arFragment: ArFragment
    private lateinit var instrText: TextView
    private lateinit var headingHelper: headingHelper

    private val headingSamples = ArrayDeque<Float>()
    private var lockedHeadingDegrees = 0f

    private val routeGeoPoints = mutableListOf<LatLng>()
    private val arrowPoints = mutableListOf<RouteArrowPoint>()

    private var startNodeId: String = ""
    private var startLatLng: LatLng? = null
    private var currentLatLng: LatLng? = null

    private var routePlaced = false
    private var lastRefreshTime = 0L
    private var stableFloorFrames = 0

    private var rootAnchorNode: AnchorNode? = null
    private var arrowRenderable: ModelRenderable? = null

    private val visibleArrowNodes = mutableMapOf<Int, Node>()

    private var destinationRenderable: ModelRenderable? = null

    private var destinationNode: Node? = null

    private var destinationLocalPosition: Vector3? = null

    private var destinationLocalRotation: Quaternion? = null


    data class RouteArrowPoint(
        val localPosition: Vector3,
        val localRotation: Quaternion
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_navigation)

        supportActionBar?.hide()

        instrText = findViewById(R.id.instrText)
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        if (!isArSupported()) {
            Toast.makeText(this, "This device does not support ARCore.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        headingHelper = headingHelper(this) { heading ->
            addHeadingSample(heading)
        }

        loadRouteFromIntent()

        if (routeGeoPoints.size < 2 || startLatLng == null) {
            Toast.makeText(this, "Route data or start node data is missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        loadArrowModel()
        loadDestinationModel()
        requestPermissionsIfNeeded()
        setupSceneUpdate()

        instrText.text = "Checking your location..."
    }

    override fun onResume() {
        super.onResume()
        headingHelper.start()
        fetchLastLocation()
    }

    override fun onPause() {
        super.onPause()
        headingHelper.stop()
    }

    private fun isArSupported(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        return availability.isSupported
    }

    private fun loadRouteFromIntent() {
        val lats = intent.getDoubleArrayExtra("route_lats")
        val lngs = intent.getDoubleArrayExtra("route_lngs")

        startNodeId = intent.getStringExtra("start_id") ?: ""
        val sLat = intent.getDoubleExtra("start_lat", Double.NaN)
        val sLng = intent.getDoubleExtra("start_lng", Double.NaN)

        if (!sLat.isNaN() && !sLng.isNaN()) {
            startLatLng = LatLng(sLat, sLng)
        }

        if (lats == null || lngs == null || lats.size != lngs.size) return

        for (i in lats.indices) {
            routeGeoPoints += LatLng(lats[i], lngs[i])
        }
    }

    private fun loadArrowModel() {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("models/direction_arrow.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable ->
                arrowRenderable = renderable
            }
            .exceptionally { error ->
                Toast.makeText(
                    this,
                    "Failed to load direction_arrow.glb: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                null
            }
    }

    private fun loadDestinationModel() {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("models/destination.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable ->
                destinationRenderable = renderable
            }
            .exceptionally { error ->
                Toast.makeText(
                    this,
                    "Failed to load destination_marker.glb: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                null
            }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.CAMERA
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            fetchLastLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastLocation() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        LocationServices.getFusedLocationProviderClient(this)
            .lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLatLng = LatLng(location.latitude, location.longitude)
                }
            }
    }

    private fun setupSceneUpdate() {
        arFragment.arSceneView.scene.addOnUpdateListener {
            val now = System.currentTimeMillis()

            if (!routePlaced) {
                if (now - lastRefreshTime >= 1000L) {
                    lastRefreshTime = now
                    fetchLastLocation()
                }

                tryAutoPlaceRoute()
                return@addOnUpdateListener
            }

            if (rootAnchorNode == null || arrowPoints.isEmpty()) return@addOnUpdateListener
            if (now - lastRefreshTime < POSITION_DETECT_DELAY_MS) return@addOnUpdateListener

            lastRefreshTime = now
            refreshVisibleArrows(force = false)
        }
    }

    private fun tryAutoPlaceRoute() {
        if (routePlaced) return

        if (arrowRenderable == null || destinationRenderable == null) {
            instrText.text = "Loading navigation models..."
            return
        }

        val start = startLatLng
        val current = currentLatLng

        if (start == null || current == null) {
            instrText.text = "Getting current location..."
            return
        }

        val distanceToStart = distanceMeters(current, start)

        if (distanceToStart > START_RADIUS_METERS) {
            stableFloorFrames = 0
            val blockName = if (startNodeId.isNotBlank()) startNodeId else "start point"
            instrText.text =
                "Go to $blockName first.\nDistance to start: ${distanceToStart.toInt()} m"
            return
        }

        val sceneView = arFragment.arSceneView
        val frame = sceneView.arFrame ?: return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            stableFloorFrames = 0
            instrText.text = "You are near the start. Move phone slowly to start AR..."
            return
        }

        val x = sceneView.width / 2f
        val y = sceneView.height * AUTO_PLACE_Y_RATIO

        val floorHit = frame.hitTest(x, y).firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                    trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.isPoseInPolygon(hit.hitPose)
        }

        if (floorHit == null) {
            stableFloorFrames = 0
            instrText.text = "You are at start. Looking for floor..."
            return
        }

        stableFloorFrames++

        if (stableFloorFrames < STABLE_FLOOR_FRAMES_REQUIRED) {
            instrText.text = "Floor detected. Hold still..."
            return
        }

        autoPlaceRoute(floorHit)
    }

    private fun autoPlaceRoute(hitResult: HitResult) {
        if (routePlaced) return

        lockedHeadingDegrees = getAverageHeading()

        val referenceLatLng = routeGeoPoints.first()

        rootAnchorNode = AnchorNode(hitResult.createAnchor()).apply {
            setParent(arFragment.arSceneView.scene)
        }

        buildArrowPath(referenceLatLng, lockedHeadingDegrees)

        addDestinationNode()

        if (arrowPoints.isEmpty()) {
            Toast.makeText(this, "Failed to build route.", Toast.LENGTH_SHORT).show()
            return
        }

        routePlaced = true
        instrText.text = "Route placed."
        refreshVisibleArrows(force = true)
    }
    private fun buildArrowPath(referenceLatLng: LatLng, headingDegrees: Float) {
        arrowPoints.clear()
        destinationLocalPosition = null
        destinationLocalRotation = null

        val converter = coordinatesHelper(referenceLatLng)

        val localNodePoints = routeGeoPoints.map { point ->
            val local = converter.getLocalCoordinates(point, headingDegrees.toDouble())
            Vector3(
                local.x.toFloat(),
                ROUTE_Y_LIFT,
                -local.y.toFloat()
            )
        }

        if (localNodePoints.size < 2) return

        val sampledPoints = densifyPath(localNodePoints, ARROW_SPACING_METERS)
        if (sampledPoints.size < 2) return

        for (i in 0 until sampledPoints.lastIndex) {
            val current = sampledPoints[i]
            val next = sampledPoints[i + 1]
            val direction = Vector3.subtract(next, current)

            if (direction.length() < 0.05f) continue

            val lookRotation = Quaternion.lookRotation(direction.normalized(), Vector3.up())
            val correctedRotation = Quaternion.multiply(
                lookRotation,
                Quaternion.axisAngle(Vector3(0f, 1f, 0f), MODEL_YAW_OFFSET_DEG)
            )

            // only add arrow for points before the final destination
            if (i < sampledPoints.lastIndex - 1) {
                arrowPoints += RouteArrowPoint(
                    localPosition = current,
                    localRotation = correctedRotation
                )
            } else {
                destinationLocalPosition = next
                destinationLocalRotation = correctedRotation
            }
        }

        // fallback if destination not set
        if (destinationLocalPosition == null) {
            destinationLocalPosition = sampledPoints.last()
            destinationLocalRotation = if (arrowPoints.isNotEmpty()) {
                arrowPoints.last().localRotation
            } else {
                Quaternion.identity()
            }
        }
    }

    private fun densifyPath(points: List<Vector3>, spacing: Float): List<Vector3> {
        if (points.size < 2) return points

        val result = mutableListOf<Vector3>()
        result += points.first()

        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]
            val segment = Vector3.subtract(end, start)
            val length = segment.length()

            if (length < 0.05f) continue

            val direction = segment.normalized()
            var distance = spacing

            while (distance < length) {
                result += Vector3.add(start, direction.scaled(distance))
                distance += spacing
            }

            result += end
        }

        return result
    }

    private fun addDestinationNode() {
        val root = rootAnchorNode ?: return
        val renderable = destinationRenderable ?: return
        val position = destinationLocalPosition ?: return
        val rotation = destinationLocalRotation ?: Quaternion.identity()

        destinationNode?.setParent(null)
        destinationNode = null

        destinationNode = Node().apply {
            setParent(root)
            this.renderable = renderable
            localPosition = position
            localRotation = rotation
            localScale = Vector3(1.3f, 1.3f, 1.3f)
        }
    }

    private fun refreshVisibleArrows(force: Boolean) {
        val root = rootAnchorNode ?: return
        if (arrowPoints.isEmpty()) return

        val cameraWorldPos = arFragment.arSceneView.scene.camera.worldPosition

        var nearestIndex = 0
        var nearestDistance = Float.MAX_VALUE

        for (i in arrowPoints.indices) {
            val worldPos = Vector3.add(root.worldPosition, arrowPoints[i].localPosition)
            val d = Vector3.subtract(worldPos, cameraWorldPos).length()
            if (d < nearestDistance) {
                nearestDistance = d
                nearestIndex = i
            }
        }

        val destinationPos = destinationLocalPosition
        if (destinationPos != null) {
            val worldDestination = Vector3.add(root.worldPosition, destinationPos)
            val distanceToDestination = Vector3.subtract(worldDestination, cameraWorldPos).length()

            if (distanceToDestination < 2.0f) {
                instrText.text = "You have arrived at your destination"

                visibleArrowNodes.values.forEach { it.setParent(null) }
                visibleArrowNodes.clear()

                return
            }
        }

        val start = max(0, nearestIndex - VISIBLE_BEHIND_COUNT)
        val end = min(arrowPoints.lastIndex, nearestIndex + VISIBLE_AHEAD_COUNT)
        val targetIndices = (start..end).toSet()

        for (i in targetIndices) {
            if (!visibleArrowNodes.containsKey(i) || force) {
                if (force) {
                    visibleArrowNodes[i]?.setParent(null)
                    visibleArrowNodes.remove(i)
                }
                addArrowNode(i)
            }
        }

        val toRemove = visibleArrowNodes.keys.filter { it !in targetIndices }
        for (index in toRemove) {
            visibleArrowNodes[index]?.setParent(null)
            visibleArrowNodes.remove(index)
        }
    }

    private fun addArrowNode(index: Int) {
        val root = rootAnchorNode ?: return
        val renderable = arrowRenderable ?: return
        val point = arrowPoints[index]

        val arrowNode = Node().apply {
            setParent(root)
            this.renderable = renderable
            localPosition = point.localPosition
            localRotation = point.localRotation
            localScale = Vector3(0.4f, 0.4f, 0.4f)
        }

        visibleArrowNodes[index] = arrowNode
    }

    private fun addHeadingSample(heading: Float) {
        if (headingSamples.size >= 15) {
            headingSamples.removeFirst()
        }
        headingSamples.addLast(normalizeAngle(heading))
    }

    private fun getAverageHeading(): Float {
        if (headingSamples.isEmpty()) return 0f

        var sumSin = 0.0
        var sumCos = 0.0

        for (angle in headingSamples) {
            val rad = Math.toRadians(angle.toDouble())
            sumSin += kotlin.math.sin(rad)
            sumCos += kotlin.math.cos(rad)
        }

        val avgRad = kotlin.math.atan2(sumSin / headingSamples.size, sumCos / headingSamples.size)
        return Math.toDegrees(avgRad).toFloat()
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a < 0f) a += 360f
        while (a >= 360f) a -= 360f
        return a
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            a.latitude, a.longitude,
            b.latitude, b.longitude,
            result
        )
        return result[0]
    }

    override fun onDestroy() {
        super.onDestroy()

        visibleArrowNodes.values.forEach { it.setParent(null) }
        visibleArrowNodes.clear()

        rootAnchorNode?.anchor?.detach()
        rootAnchorNode?.setParent(null)
        rootAnchorNode = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                fetchLastLocation()
            } else {
                Toast.makeText(
                    this,
                    "Camera and location permissions are required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}