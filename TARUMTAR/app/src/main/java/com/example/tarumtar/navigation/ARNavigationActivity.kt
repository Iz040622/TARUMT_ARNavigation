package com.example.tarumtar.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.os.Looper
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tarumtar.R
import com.example.tarumtar.petModule.PointManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.ArCoreApk
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.core.Config
import com.google.ar.sceneform.rendering.Light
import com.google.ar.sceneform.rendering.Color as ArColor
import com.example.tarumtar.ui.MainActivity
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
        private const val PET_MODEL_YAW_OFFSET = 180f
    }

    private lateinit var arFragment: ArFragment
    private lateinit var instrText: TextView
    private lateinit var btnRecenter: Button
    private lateinit var compassImage: ImageView
    private lateinit var compassText: TextView
    private lateinit var headingHelper: headingHelper
    private var locationCallback: LocationCallback? = null

    private val headingSamples = ArrayDeque<Float>()
    private var lockedHeadingDegrees = 0f

    private val routeGeoPoints = mutableListOf<LatLng>()
    private val arrowPoints = mutableListOf<RouteArrowPoint>()

    private var startNodeId: String = ""
    private var startLatLng: LatLng? = null
    private var currentLatLng: LatLng? = null

    private var routePlaced = false
    private var isRecentering = false
    private var currentNearestArrowIndex = 0
    private var lastRefreshTime = 0L
    private var stableFloorFrames = 0

    private var rootAnchorNode: AnchorNode? = null
    private var pathContainer: Node? = null
    private var arrowRenderable: ModelRenderable? = null
    private var destinationRenderable: ModelRenderable? = null

    private val visibleArrowNodes = mutableMapOf<Int, Node>()

    private lateinit var pointManager: PointManager
    private var lastPointLatLng: LatLng? = null
    private var totalDistanceWalked = 0f

    private var petNode: Node? = null
    private var petRenderable: ModelRenderable? = null
    private var isArrivalTriggered = false
    private var isPetReacting = false

    data class RouteArrowPoint(
        val localPosition: Vector3,
        val localRotation: Quaternion
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_navigation)

        supportActionBar?.hide()

        instrText = findViewById(R.id.instrText)
        btnRecenter = findViewById(R.id.btnRecenter)
        compassImage = findViewById(R.id.compassImage)
        compassText = findViewById(R.id.compassText)

        btnRecenter.setOnClickListener {
            resetRoute()
        }

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        arFragment.setOnSessionConfigurationListener { _, config ->
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.focusMode = Config.FocusMode.AUTO
        }

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
        requestPermissionsIfNeeded()
        setupSceneUpdate()

        pointManager = PointManager(this)
        loadPetModel()

        instrText.text = "Checking your location..."
    }

    override fun onResume() {
        super.onResume()
        headingHelper.start()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        headingHelper.stop()
        stopLocationUpdates()
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
                    "Failed to load destination.glb: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                null
            }
    }

    private fun loadPetModel() {
        val selectedPet = pointManager.getSelectedPet()
        val assetPath = PointManager.PET_ASSETS[selectedPet] ?: return

        ModelRenderable.builder()
            .setSource(this, Uri.parse(assetPath))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { renderable ->
                petRenderable = renderable
            }
            .exceptionally {
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
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val newLatLng = LatLng(location.latitude, location.longitude)

                    if (lastPointLatLng != null && location.accuracy < 15f) {
                        val dist = distanceMeters(lastPointLatLng!!, newLatLng)
                        // Filter out small GPS jumps/noise by requiring at least 2m of movement
                        if (dist > 2f) {
                            totalDistanceWalked += dist
                            if (totalDistanceWalked >= 1f) {
                                val pointsEarned = totalDistanceWalked.toInt()
                                pointManager.addPoints(pointsEarned)
                                totalDistanceWalked -= pointsEarned
                            }
                            // Only update baseline when we've actually moved significantly
                            lastPointLatLng = newLatLng
                        }
                    } else if (lastPointLatLng == null) {
                        lastPointLatLng = newLatLng
                    }
                    currentLatLng = newLatLng
                }
            }
        }

        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it)
        }
    }

    private fun setupSceneUpdate() {
        arFragment.arSceneView.scene.addOnUpdateListener {
            val now = System.currentTimeMillis()

            if (!routePlaced) {
                tryAutoPlaceRoute()
                return@addOnUpdateListener
            }

            if (rootAnchorNode == null || arrowPoints.isEmpty()) return@addOnUpdateListener
            if (now - lastRefreshTime < POSITION_DETECT_DELAY_MS) return@addOnUpdateListener

            lastRefreshTime = now
            refreshVisibleArrows(force = false)

            if (petNode != null) {
                updatePetWaiting()
            } else if (routePlaced && petRenderable != null) {
                spawnPet()
            }
        }
    }

    private fun spawnPet() {
        val root = pathContainer ?: return
        val renderable = petRenderable ?: return
        if (arrowPoints.isEmpty()) return

        // Destination is the last point in the arrow sequence
        val destinationPoint = arrowPoints.last()

        petNode = Node().apply {
            setParent(root)
            this.renderable = renderable
            val selectedPet = pointManager.getSelectedPet()
            val scale = PointManager.PET_SCALES[selectedPet] ?: 0.15f
            localScale = Vector3(scale, scale, scale)

            // Set position to destination
            localPosition = destinationPoint.localPosition
            // Lift slightly to avoid z-fighting with the destination marker
            localPosition = Vector3(localPosition.x, localPosition.y + 0.01f, localPosition.z)
            
            // Set initial rotation
            localRotation = destinationPoint.localRotation

            // Add a point light slightly above the pet to make it brighter
            val petParent = this
            Node().apply {
                setParent(petParent)
                localPosition = Vector3(0f, 0.5f, 0f)
                light = Light.builder(Light.Type.POINT)
                    .setColor(ArColor(1f, 1f, 1f))
                    .setIntensity(2000f)
                    .setFalloffRadius(2.0f)
                    .build()
            }
        }
        
        startWaitingAnimation()
    }

    private fun startWaitingAnimation() {
        val pet = petNode ?: return
        val basePos = pet.localPosition
        
        val animator = ValueAnimator.ofFloat(0f, 0.04f, 0f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val offset = anim.animatedValue as Float
                pet.localPosition = Vector3(basePos.x, basePos.y + offset, basePos.z)
            }
        }
        animator.start()
    }

    private fun updatePetWaiting() {
        val pet = petNode ?: return
        if (isPetReacting) return

        val camera = arFragment.arSceneView.scene.camera
        val cameraPos = camera.worldPosition
        
        val petPos = pet.worldPosition
        val diff = Vector3.subtract(cameraPos, petPos)
        diff.y = 0f
        
        val distance = diff.length()

        // Check for arrival
        if (distance < 1.5f && !isArrivalTriggered) {
            triggerArrival()
        }

        if (distance > 0.1f) {
            val direction = diff.normalized()
            val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
            val correction = Quaternion.axisAngle(Vector3(0f, 1f, 0f), PET_MODEL_YAW_OFFSET)
            // Smoothly rotate to face user with correction
            pet.worldRotation = Quaternion.slerp(pet.worldRotation, Quaternion.multiply(lookRotation, correction), 0.05f)
        }
    }

    private fun triggerArrival() {
        isArrivalTriggered = true
        isPetReacting = true
        
        playHappyAnimation {
            Toast.makeText(this, "You have arrived at your destination!", Toast.LENGTH_LONG).show()
            instrText.postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }, 3000)
        }
    }

    private fun playHappyAnimation(onEnd: () -> Unit) {
        val pet = petNode ?: return
        val basePos = pet.worldPosition
        val baseRot = pet.worldRotation

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // Bobbing and spinning
                val bob = kotlin.math.abs(kotlin.math.sin(t * kotlin.math.PI * 4)).toFloat() * 0.15f
                val spin = t * 360f * 2
                
                pet.worldPosition = Vector3(basePos.x, basePos.y + bob, basePos.z)
                val spinRot = Quaternion.axisAngle(Vector3(0f, 1f, 0f), spin)
                pet.worldRotation = Quaternion.multiply(baseRot, spinRot)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pet.worldPosition = basePos
                    pet.worldRotation = baseRot
                    onEnd()
                }
            })
            start()
        }
    }

    private fun tryAutoPlaceRoute() {
        if (routePlaced) return

        val renderable = arrowRenderable
        if (renderable == null) {
            instrText.text = "Loading arrow model..."
            return
        }

        val start = startLatLng
        val current = currentLatLng

        if (start == null || current == null) {
            instrText.text = "Getting current location..."
            return
        }

        val distanceToStart = distanceMeters(current, start)

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

        rootAnchorNode = AnchorNode(hitResult.createAnchor()).apply {
            setParent(arFragment.arSceneView.scene)
        }
        
        pathContainer = Node().apply {
            setParent(rootAnchorNode)
            worldRotation = Quaternion.identity()
        }

        if (isRecentering && arrowPoints.isNotEmpty()) {
            val offset = arrowPoints[0].localPosition
            for (index in arrowPoints.indices) {
                arrowPoints[index] = arrowPoints[index].copy(
                    localPosition = Vector3.subtract(arrowPoints[index].localPosition, offset)
                )
            }
            isRecentering = false
        } else {
            lockedHeadingDegrees = getAverageHeading()
            val referenceLatLng = routeGeoPoints.first()
            buildArrowPath(referenceLatLng, lockedHeadingDegrees)
        }

        if (arrowPoints.isEmpty()) {
            Toast.makeText(this, "Failed to build route.", Toast.LENGTH_SHORT).show()
            return
        }

        routePlaced = true
        instrText.text = "Route placed."
        btnRecenter.visibility = View.VISIBLE
        refreshVisibleArrows(force = true)
    }

    private fun resetRoute() {
        if (!routePlaced || arrowPoints.isEmpty()) return

        if (currentNearestArrowIndex > 0 && currentNearestArrowIndex < arrowPoints.size) {
            val toRemove = arrowPoints.subList(0, currentNearestArrowIndex).toList()
            arrowPoints.removeAll(toRemove)
            currentNearestArrowIndex = 0
        }

        isRecentering = true
        routePlaced = false

        visibleArrowNodes.values.forEach { it.setParent(null) }
        visibleArrowNodes.clear()

        pathContainer?.setParent(null)
        pathContainer = null

        rootAnchorNode?.anchor?.detach()
        rootAnchorNode?.setParent(null)
        rootAnchorNode = null

        stableFloorFrames = 0
        instrText.text = "Recentering... look at the floor."
        btnRecenter.visibility = View.GONE
    }

    private fun buildArrowPath(referenceLatLng: LatLng, headingDegrees: Float) {
        arrowPoints.clear()

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

            arrowPoints += RouteArrowPoint(
                localPosition = current,
                localRotation = correctedRotation
            )
        }

        if (arrowPoints.isNotEmpty()) {
            arrowPoints += RouteArrowPoint(
                localPosition = sampledPoints.last(),
                localRotation = arrowPoints.last().localRotation
            )
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

    private fun refreshVisibleArrows(force: Boolean) {
        val root = pathContainer ?: return
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

        currentNearestArrowIndex = nearestIndex

        val start = max(0, nearestIndex - VISIBLE_BEHIND_COUNT)
        val end = min(arrowPoints.lastIndex, nearestIndex + VISIBLE_AHEAD_COUNT)
        val targetIndices = (start..end).toMutableSet()
        
        // Always render the final destination marker so the user knows where they are heading!
        if (arrowPoints.isNotEmpty()) {
            targetIndices.add(arrowPoints.lastIndex)
        }

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
        val root = pathContainer ?: return
        val point = arrowPoints[index]

        val isDestination = (index == arrowPoints.lastIndex)
        val renderableToUse = if (isDestination) destinationRenderable else arrowRenderable

        if (renderableToUse == null) return

        val arrowNode = Node().apply {
            setParent(root)
            this.renderable = renderableToUse
            localPosition = point.localPosition
            
            if (isDestination) {
                localScale = Vector3(1.5f, 1.5f, 1.5f)
                val extraRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 90f)
                localRotation = Quaternion.multiply(point.localRotation, extraRotation)
            } else {
                localScale = Vector3(0.4f, 0.4f, 0.4f)
                localRotation = point.localRotation
            }
        }

        visibleArrowNodes[index] = arrowNode
    }

    private fun addHeadingSample(heading: Float) {
        if (headingSamples.size >= 15) {
            headingSamples.removeFirst()
        }
        val normalized = normalizeAngle(heading)
        headingSamples.addLast(normalized)

        compassImage.rotation = -normalized

        val compassDir = when (normalized) {
            in 337.5..360.0, in 0.0..22.5 -> "N"
            in 22.5..67.5 -> "NE"
            in 67.5..112.5 -> "E"
            in 112.5..157.5 -> "SE"
            in 157.5..202.5 -> "S"
            in 202.5..247.5 -> "SW"
            in 247.5..292.5 -> "W"
            in 292.5..337.5 -> "NW"
            else -> ""
        }
        compassText.text = "${normalized.toInt()}° $compassDir"
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
                startLocationUpdates()
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