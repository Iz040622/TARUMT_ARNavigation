package com.example.tarumtar.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class ARNavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2001

        private const val POSITION_DETECT_DELAY_MS = 100L
        private const val VISIBLE_AHEAD_COUNT = 15
        private const val VISIBLE_BEHIND_COUNT = 2

        private const val ARROW_SPACING_METERS = 1.2f
        private const val MODEL_YAW_OFFSET_DEG = 90f
        private const val ROUTE_Y_LIFT = 0.08f

        private const val AUTO_PLACE_Y_RATIO = 0.72f
        private const val STABLE_FLOOR_FRAMES_REQUIRED = 8
        private const val PET_MODEL_YAW_OFFSET = 180f

        // Angle thresholds for turn detection
        private const val STRAIGHT_THRESHOLD_DEG = 25f
        private const val TURN_THRESHOLD_DEG = 160f

        // How many arrows from the last index counts as "near destination"
        private const val NEAR_DESTINATION_COUNT = 4
    }

    private lateinit var arFragment: ArFragment
    private lateinit var instrText: TextView
    private lateinit var btnRecenter: Button
    private lateinit var compassImage: ImageView
    private lateinit var compassText: TextView
    private lateinit var headingHelper: headingHelper
    private var locationCallback: LocationCallback? = null

    // Language & TTS
    private var currentLanguage: String = "EN"
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var btnLangEn: Button
    private lateinit var btnLangZh: Button
    private lateinit var btnLangMs: Button

    // Throttle repeated status speech so frame-loop messages don't spam audio
    private var lastStatusKey: String = ""
    private var lastStatusSpeakTime: Long = 0L
    private val STATUS_SPEAK_COOLDOWN_MS = 4000L   // speak same key at most every 4 s

    // Direction HUD
    private lateinit var layoutNavHud: LinearLayout
    private lateinit var navDirectionIcon: ImageView
    private lateinit var navDirectionText: TextView
    private var lastInstruction: String = ""

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

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContentView(R.layout.activity_ar_navigation)

        supportActionBar?.hide()

        instrText = findViewById(R.id.instrText)
        btnRecenter = findViewById(R.id.btnRecenter)
        compassImage = findViewById(R.id.compassImage)
        compassText = findViewById(R.id.compassText)

        // Language buttons
        btnLangEn = findViewById(R.id.btnLangEn)
        btnLangZh = findViewById(R.id.btnLangZh)
        btnLangMs = findViewById(R.id.btnLangMs)

        btnLangEn.setOnClickListener { setLanguage("EN") }
        btnLangZh.setOnClickListener { setLanguage("ZH") }
        btnLangMs.setOnClickListener { setLanguage("MS") }

        // Direction HUD
        layoutNavHud = findViewById(R.id.layoutNavHud)
        navDirectionIcon = findViewById(R.id.navDirectionIcon)
        navDirectionText = findViewById(R.id.navDirectionText)

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

        // Read language from intent
        currentLanguage = intent.getStringExtra("selected_language") ?: "EN"
        updateLanguageButtonVisual()

        // Init TTS
        tts = TextToSpeech(this, this)

        loadRouteFromIntent()

        if (routeGeoPoints.size < 2 || startLatLng == null) {
            Toast.makeText(this, "Route data or start node data is missing.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        loadArrowModel()
        setupSceneUpdate()

        pointManager = PointManager(this)
        loadPetModel()

        instrText.text = getInstruction("checking_location")
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

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()

        visibleArrowNodes.values.forEach { it.setParent(null) }
        visibleArrowNodes.clear()

        rootAnchorNode?.anchor?.detach()
        rootAnchorNode?.setParent(null)
        rootAnchorNode = null
    }

    // =========================================================================
    // TTS
    // =========================================================================

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsLocale()
            ttsReady = true
            // Greet in the selected language after a short delay
            instrText.postDelayed({
                speak(getInstruction("checking_location"))
            }, 800)
        }
    }

    private fun applyTtsLocale() {
        val engine = tts ?: return

        val locale: Locale = when (currentLanguage) {
            "ZH" -> Locale.SIMPLIFIED_CHINESE

            "MS" -> {
                // Prefer Malay (ms-MY). Most Google TTS engines support it;
                // fall back to Indonesian (in-ID) which is near-identical phonetically.
                val ms = Locale("ms", "MY")
                val msResult = engine.isLanguageAvailable(ms)
                if (msResult == TextToSpeech.LANG_AVAILABLE ||
                    msResult == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    msResult == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    ms
                } else {
                    val id = Locale("in", "ID")
                    val idResult = engine.isLanguageAvailable(id)
                    if (idResult != TextToSpeech.LANG_MISSING_DATA &&
                        idResult != TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        id   // Indonesian fallback
                    } else {
                        Locale.ENGLISH  // last resort
                    }
                }
            }

            else -> Locale.ENGLISH
        }

        // setLanguage() returns a result code — check it and inform the user if the
        // language data is missing (property setter 'tts.language = x' silently ignores this).
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val langName = when (currentLanguage) {
                "ZH" -> "Chinese"
                "MS" -> "Malay"
                else -> currentLanguage
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "$langName TTS voice not installed on this device. Voice will use English.",
                    Toast.LENGTH_LONG
                ).show()
            }
            engine.setLanguage(Locale.ENGLISH)   // hard fallback
        }
    }


    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_tts")
        }
    }

    /**
     * Sets the status TextView text AND speaks it via TTS.
     * Mutes "nav_active" completely from audio so it never interrupts Turn-By-Turn directions.
     */
    private fun showStatus(key: String) {
        val text = getInstruction(key)
        
        // Prevent layout thrashing
        if (instrText.text != text) {
            instrText.text = text
        }

        if (!ttsReady) return

        val now = System.currentTimeMillis()
        val sameKey = (key == lastStatusKey)
        
        // Only allow a repeating announcement if we are actively stuck losing tracking
        val shouldRepeat = sameKey && key == "tracking_lost" && (now - lastStatusSpeakTime) >= STATUS_SPEAK_COOLDOWN_MS

        if (!sameKey || shouldRepeat) {
            lastStatusKey = key
            lastStatusSpeakTime = now
            
            // Never speak the steady-state 'nav_active' string. It's just visual. 
            // Speaking it constantly interrupts real turn instructions.
            if (key != "nav_active") {
                speak(text)
            }
        }
    }

    // =========================================================================
    // LANGUAGE SWITCHING
    // =========================================================================

    private fun setLanguage(lang: String) {
        currentLanguage = lang
        updateLanguageButtonVisual()
        applyTtsLocale()
        // Re-announce current instruction in new language
        if (lastInstruction.isNotEmpty()) {
            updateDirectionHud(lastInstruction, announce = true)
        }
    }

    private fun updateLanguageButtonVisual() {
        val activeColor = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#1E88E5")
        )
        val inactiveColor = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#444444")
        )
        btnLangEn.backgroundTintList = if (currentLanguage == "EN") activeColor else inactiveColor
        btnLangZh.backgroundTintList = if (currentLanguage == "ZH") activeColor else inactiveColor
        btnLangMs.backgroundTintList = if (currentLanguage == "MS") activeColor else inactiveColor
    }

    // =========================================================================
    // TRANSLATION TABLE
    // =========================================================================

    private fun getInstruction(key: String): String {
        return when (currentLanguage) {
            "ZH" -> when (key) {
                "go_straight"        -> "直行"
                "turn_left"          -> "向左转"
                "turn_right"         -> "向右转"
                "arrived"            -> "您已到达目的地！"
                "nav_active"         -> "导航进行中"
                "tracking_lost"      -> "追踪丢失，请缓慢移动手机..."
                "loading_arrow"      -> "加载箭头模型..."
                "getting_location"   -> "正在获取当前位置..."
                "near_start"         -> "您在起点附近，请缓慢移动手机以开始AR..."
                "floor_detected"     -> "地板已检测到，请保持稳定..."
                "hold_still"         -> "找到地板，请保持静止..."
                "route_placed"       -> "路线已放置。"
                "recentering"        -> "重新定位中...请看向地板。"
                "checking_location"  -> "正在检查您的位置..."
                else                 -> key
            }
            "MS" -> when (key) {
                "go_straight"        -> "Pergi lurus"
                "turn_left"          -> "Belok kiri"
                "turn_right"         -> "Belok kanan"
                "arrived"            -> "Anda telah tiba di destinasi!"
                "nav_active"         -> "Navigasi aktif"
                "tracking_lost"      -> "Penjejakan hilang. Gerak telefon perlahan-lahan..."
                "loading_arrow"      -> "Memuatkan model anak panah..."
                "getting_location"   -> "Mendapatkan lokasi semasa..."
                "near_start"         -> "Anda berhampiran titik mula. Gerak telefon perlahan untuk memulakan AR..."
                "floor_detected"     -> "Lantai dikesan. Tahan sebentar..."
                "hold_still"         -> "Lantai dikesan. Sila jangan bergerak..."
                "route_placed"       -> "Laluan telah diletakkan."
                "recentering"        -> "Memusatkan semula... sila lihat ke lantai."
                "checking_location"  -> "Sedang menyemak lokasi anda..."
                else                 -> key
            }
            else -> when (key) {
                "go_straight"        -> "Go Straight"
                "turn_left"          -> "Turn Left"
                "turn_right"         -> "Turn Right"
                "arrived"            -> "You have arrived at your destination!"
                "nav_active"         -> "Navigation Active"
                "tracking_lost"      -> "Tracking lost. Move phone slowly to recover..."
                "loading_arrow"      -> "Loading arrow model..."
                "getting_location"   -> "Getting current location..."
                "near_start"         -> "You are near the start. Move phone slowly to start AR..."
                "floor_detected"     -> "Floor detected. Hold still..."
                "hold_still"         -> "Floor detected. Hold still..."
                "route_placed"       -> "Route placed."
                "recentering"        -> "Recentering... look at the floor."
                "checking_location"  -> "Checking your location..."
                else                 -> key
            }
        }
    }

    // =========================================================================
    // DIRECTION HUD
    // =========================================================================

    private fun updateDirectionHud(instructionKey: String, announce: Boolean = false) {
        val changed = instructionKey != lastInstruction
        lastInstruction = instructionKey

        val iconRes = when (instructionKey) {
            "turn_left"  -> R.drawable.ic_nav_left
            "turn_right" -> R.drawable.ic_nav_right
            "arrived"    -> R.drawable.ic_nav_destination
            else         -> R.drawable.ic_nav_straight   // go_straight
        }
        navDirectionIcon.setImageResource(iconRes)
        navDirectionText.text = getInstruction(instructionKey)
        layoutNavHud.visibility = View.VISIBLE

        if ((changed || announce) && ttsReady) {
            speak(getInstruction(instructionKey))
        }
    }

    // =========================================================================
    // TURN DETECTION
    // =========================================================================

    /**
     * Detects the turn type at [nearestIndex] by comparing directions over a wide
     * look-behind and look-ahead window (~9.6 m each side at 1.2 m arrow spacing).
     *
     * Comparing only adjacent densified points gives tiny per-step angles (~9° for a
     * 90° turn spread over 10 points), which always falls under the straight threshold.
     * Using a wider window accumulates the real turn angle reliably.
     */
    private fun computeTurnInstruction(nearestIndex: Int): String {
        val total = arrowPoints.size

        // Near end of route → destination
        if (nearestIndex >= total - NEAR_DESTINATION_COUNT) {
            return "arrived"
        }

        // Wide window: 8 points × 1.2 m spacing ≈ 9.6 m each side.
        // This ensures we see the full accumulated turn angle, not just tiny per-step deltas.
        val LOOK_WINDOW = 8
        val behindIdx = max(0, nearestIndex - LOOK_WINDOW)
        val aheadIdx  = min(total - 1, nearestIndex + LOOK_WINDOW)

        if (behindIdx >= aheadIdx) return "go_straight"

        val behind = arrowPoints[behindIdx].localPosition
        val curr   = arrowPoints[nearestIndex].localPosition
        val ahead  = arrowPoints[aheadIdx].localPosition

        // Incoming direction: from the behind sample to the current position
        val inDirX = curr.x - behind.x
        val inDirZ = curr.z - behind.z

        // Outgoing direction: from the current position to the ahead sample
        val outDirX = ahead.x - curr.x
        val outDirZ = ahead.z - curr.z

        val inLen  = kotlin.math.sqrt((inDirX * inDirX + inDirZ * inDirZ).toDouble()).toFloat()
        val outLen = kotlin.math.sqrt((outDirX * outDirX + outDirZ * outDirZ).toDouble()).toFloat()

        if (inLen < 0.01f || outLen < 0.01f) return "go_straight"

        val inNX = inDirX / inLen;   val inNZ = inDirZ / inLen
        val outNX = outDirX / outLen; val outNZ = outDirZ / outLen

        // Dot product → total angle between incoming and outgoing directions
        val dot = (inNX * outNX + inNZ * outNZ).coerceIn(-1f, 1f)
        val angleDeg = Math.toDegrees(kotlin.math.acos(dot.toDouble())).toFloat()

        // 2D cross product in the XZ plane:
        //   cross > 0 → right turn (e.g. going -Z then curving to +X)
        //   cross < 0 → left turn  (e.g. going -Z then curving to -X)
        val cross = inNX * outNZ - inNZ * outNX

        return when {
            angleDeg < STRAIGHT_THRESHOLD_DEG -> "go_straight"
            angleDeg > TURN_THRESHOLD_DEG     -> "go_straight"   // near U-turn — treat as straight
            cross < 0f                         -> "turn_left"
            else                               -> "turn_right"
        }
    }

    // =========================================================================
    // AR CORE SETUP
    // =========================================================================

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
                Toast.makeText(this, "Failed to load direction_arrow.glb: ${error.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "Failed to load destination.glb: ${error.message}", Toast.LENGTH_LONG).show()
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
            .exceptionally { null }
    }

    // =========================================================================
    // PERMISSIONS & LOCATION
    // =========================================================================

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.ACCESS_COARSE_LOCATION

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
                        if (dist > 2f) {
                            totalDistanceWalked += dist
                            if (totalDistanceWalked >= 1f) {
                                val pointsEarned = totalDistanceWalked.toInt()
                                pointManager.addPoints(pointsEarned)
                                totalDistanceWalked -= pointsEarned
                            }
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

    // =========================================================================
    // SCENE UPDATE LOOP
    // =========================================================================

    private fun setupSceneUpdate() {
        arFragment.arSceneView.scene.addOnUpdateListener {
            val now = System.currentTimeMillis()

            if (!routePlaced) {
                tryAutoPlaceRoute()
                return@addOnUpdateListener
            }

            if (rootAnchorNode == null || arrowPoints.isEmpty()) return@addOnUpdateListener
            if (now - lastRefreshTime < POSITION_DETECT_DELAY_MS) return@addOnUpdateListener

            val frame = arFragment.arSceneView.arFrame
            if (frame?.camera?.trackingState != TrackingState.TRACKING) {
                if (routePlaced) {
                    showStatus("tracking_lost")
                }
                return@addOnUpdateListener
            }

            if (routePlaced) {
                showStatus("nav_active")
            }

            lastRefreshTime = now
            refreshVisibleArrows(force = false)

            if (petNode != null) {
                updatePetWaiting()
            } else if (routePlaced && petRenderable != null) {
                spawnPet()
            }
        }
    }

    // =========================================================================
    // PET COMPANION
    // =========================================================================

    private fun spawnPet() {
        val root = pathContainer ?: return
        val renderable = petRenderable ?: return
        if (arrowPoints.isEmpty()) return

        val destinationPoint = arrowPoints.last()

        petNode = Node().apply {
            setParent(root)
            this.renderable = renderable
            val selectedPet = pointManager.getSelectedPet()
            val scale = PointManager.PET_SCALES[selectedPet] ?: 0.15f
            localScale = Vector3(scale, scale, scale)
            localPosition = destinationPoint.localPosition
            localPosition = Vector3(localPosition.x, localPosition.y + 0.01f, localPosition.z)
            localRotation = destinationPoint.localRotation

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

        if (distance < 1.5f && !isArrivalTriggered) {
            triggerArrival()
        }

        if (distance > 0.1f) {
            val direction = diff.normalized()
            val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
            val correction = Quaternion.axisAngle(Vector3(0f, 1f, 0f), PET_MODEL_YAW_OFFSET)
            pet.worldRotation = Quaternion.slerp(pet.worldRotation, Quaternion.multiply(lookRotation, correction), 0.05f)
        }
    }

    private fun triggerArrival() {
        isArrivalTriggered = true
        isPetReacting = true

        // Show arrived in HUD
        updateDirectionHud("arrived", announce = true)

        playHappyAnimation {
            Toast.makeText(this, getInstruction("arrived"), Toast.LENGTH_LONG).show()
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

    // =========================================================================
    // ROUTE PLACEMENT
    // =========================================================================

    private fun tryAutoPlaceRoute() {
        if (routePlaced) return

        val renderable = arrowRenderable
        if (renderable == null) {
            showStatus("loading_arrow")
            return
        }

        val start = startLatLng
        val current = currentLatLng

        if (start == null || current == null) {
            showStatus("getting_location")
            return
        }

        val sceneView = arFragment.arSceneView
        val frame = sceneView.arFrame ?: return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            stableFloorFrames = 0
            showStatus("near_start")
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
            showStatus("floor_detected")
            return
        }

        stableFloorFrames++

        if (stableFloorFrames < STABLE_FLOOR_FRAMES_REQUIRED) {
            showStatus("hold_still")
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
        showStatus("route_placed")
        btnRecenter.visibility = View.VISIBLE
        refreshVisibleArrows(force = true)

        // Show initial direction HUD
        val initialInstruction = computeTurnInstruction(0)
        updateDirectionHud(initialInstruction, announce = true)
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
        lastInstruction = ""
        lastStatusKey = ""

        visibleArrowNodes.values.forEach { it.setParent(null) }
        visibleArrowNodes.clear()

        pathContainer?.setParent(null)
        pathContainer = null

        // Bug fix: The pet was tied to the old pathContainer.
        // We must clear it here so it predictably respawns on the new path when recentered.
        petNode?.setParent(null)
        petNode = null

        rootAnchorNode?.anchor?.detach()
        rootAnchorNode?.setParent(null)
        rootAnchorNode = null

        stableFloorFrames = 0
        showStatus("recentering")
        btnRecenter.visibility = View.GONE
        layoutNavHud.visibility = View.GONE
    }

    // =========================================================================
    // ARROW PATH BUILDING
    // =========================================================================

    private fun buildArrowPath(referenceLatLng: LatLng, headingDegrees: Float) {
        arrowPoints.clear()

        val converter = coordinatesHelper(referenceLatLng)

        val localNodePoints = routeGeoPoints.map { point ->
            val local = converter.getLocalCoordinates(point, headingDegrees.toDouble())
            Vector3(local.x.toFloat(), ROUTE_Y_LIFT, -local.y.toFloat())
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

            arrowPoints += RouteArrowPoint(localPosition = current, localRotation = correctedRotation)
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

    // =========================================================================
    // ARROW RENDERING
    // =========================================================================

    private fun refreshVisibleArrows(force: Boolean) {
        val root = pathContainer ?: return
        if (arrowPoints.isEmpty()) return

        val cameraWorldPos = arFragment.arSceneView.scene.camera.worldPosition

        var nearestIndex = 0
        var nearestDistanceSq = Float.MAX_VALUE

        val rootPos = root.worldPosition
        val camX = cameraWorldPos.x
        val camY = cameraWorldPos.y
        val camZ = cameraWorldPos.z

        // Highly optimized distance loop: avoids ANY object allocation (like Vector3.add)
        // Do NOT revert this! Allocating new Vectors here 10x a second causes extreme GC lag.
        for (i in arrowPoints.indices) {
            val local = arrowPoints[i].localPosition
            val dx = rootPos.x + local.x - camX
            val dy = rootPos.y + local.y - camY
            val dz = rootPos.z + local.z - camZ
            
            val dSq = dx * dx + dy * dy + dz * dz
            if (dSq < nearestDistanceSq) {
                nearestDistanceSq = dSq
                nearestIndex = i
            }
        }

        val prevNearestIndex = currentNearestArrowIndex
        currentNearestArrowIndex = nearestIndex

        // Update direction HUD only when the nearest index changes
        if (nearestIndex != prevNearestIndex && !isArrivalTriggered) {
            val instruction = computeTurnInstruction(nearestIndex)
            updateDirectionHud(instruction)
        }

        val start = max(0, nearestIndex - VISIBLE_BEHIND_COUNT)
        val end = min(arrowPoints.lastIndex, nearestIndex + VISIBLE_AHEAD_COUNT)
        val targetIndices = (start..end).toMutableSet()

        // Always render the final destination marker
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

    // =========================================================================
    // COMPASS / HEADING
    // =========================================================================

    private fun addHeadingSample(heading: Float) {
        if (headingSamples.size >= 15) {
            headingSamples.removeFirst()
        }
        val normalized = normalizeAngle(heading)
        headingSamples.addLast(normalized)

        compassImage.rotation = -normalized

        val compassDir = when (normalized) {
            in 337.5..360.0, in 0.0..22.5 -> "N"
            in 22.5..67.5                  -> "NE"
            in 67.5..112.5                 -> "E"
            in 112.5..157.5                -> "SE"
            in 157.5..202.5                -> "S"
            in 202.5..247.5                -> "SW"
            in 247.5..292.5                -> "W"
            in 292.5..337.5                -> "NW"
            else                           -> ""
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

        val avgRad = atan2(sumSin / headingSamples.size, sumCos / headingSamples.size)
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
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    // =========================================================================
    // PERMISSIONS RESULT
    // =========================================================================

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
                Toast.makeText(this, "Camera and location permissions are required.", Toast.LENGTH_LONG).show()
            }
        }
    }
}