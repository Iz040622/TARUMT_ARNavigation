package com.example.tarumtar

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.scanObject.CameraPermissionHelper
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.*

class Navigation : AppCompatActivity() {

    private val TAG = "Navigation"
    private var installRequested = false
    private var session: Session? = null
    private var shouldConfigureSession = false

    private lateinit var arSceneView: ArSceneView
    private lateinit var tvStatus: TextView

    // ---- Directions ----
    private val directionsApiKey = "AIzaSyBj9ss3fbGDw9oGlHORMuXGPZF-ctYyDIs"

    private val destinationLat = 3.215469435681961
    private val destinationLng = 101.72650345115474

    data class LatLng(val lat: Double, val lng: Double)

    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var junctions: List<LatLng> = emptyList()
    private var currentIndex = 0
    private var routeRequested = false

    // ---- Arrow ----
    private var arrowRenderable: ModelRenderable? = null
    private var arrowAnchorNode: AnchorNode? = null

    private val arriveThresholdMeters = 8.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.navigation)

        arSceneView = findViewById(R.id.arSceneView)
        tvStatus = findViewById(R.id.tvStatus)

        arSceneView.scene.addOnUpdateListener(this::onUpdateFrame)

        // Load arrow model once
        ModelRenderable.builder()
            .setSource(this, android.net.Uri.parse("models/direction_arrow.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept { arrowRenderable = it }
            .exceptionally {
                tvStatus.text = "Arrow model load failed: ${it.message}"
                Log.e(TAG, "Model load error", it)
                null
            }
    }

    private fun configureSession() {
        val s = session ?: return
        val config = Config(s)

        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        // Enable Geospatial
        config.geospatialMode = Config.GeospatialMode.ENABLED

        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

        s.configure(config)
    }

    private fun onUpdateFrame(frameTime: FrameTime) {
        val frame = arSceneView.arFrame ?: return
        val s = session ?: return

        val earth = s.earth
        if (earth == null) {
            tvStatus.text = "Earth API not available (device/ARCore not supported)"
            return
        }
        else if(earth.trackingState != TrackingState.TRACKING){
            tvStatus.text = "Earth not tracking… (GPS+internet, go outdoor)"
            return
        }


        val pose = earth.cameraGeospatialPose
        val camLat = pose.latitude
        val camLng = pose.longitude
        val camAlt = pose.altitude

        // fetch route once
        if (junctions.isEmpty()) {
            if (!routeRequested) {
                routeRequested = true
                tvStatus.text = "Fetching route…"
                fetchRoute(camLat, camLng, destinationLat, destinationLng)
            }
            return
        }

        if (currentIndex >= junctions.size) {
            tvStatus.text = "Arrived ✅"
            removeArrow()
            return
        }

        val target = junctions[currentIndex]
        val dist = haversineMeters(camLat, camLng, target.lat, target.lng)
        tvStatus.text = "Next ${currentIndex + 1}/${junctions.size}  dist=${dist.toInt()}m"

        // arrive -> next
        if (dist <= arriveThresholdMeters) {
            currentIndex++
            removeArrow()
            return
        }

        // place arrow if missing
        if (arrowAnchorNode == null && arrowRenderable != null) {
            placeArrowAtJunction(earth, target, camAlt, camLat, camLng)
        }

        // make arrow face camera (optional)
        arrowAnchorNode?.children?.firstOrNull()?.let { arrowNode ->
            val camPos = arSceneView.scene.camera.worldPosition
            val nodePos = arrowNode.worldPosition
            val dir = Vector3.subtract(camPos, nodePos)
            arrowNode.worldRotation = Quaternion.lookRotation(dir, Vector3.up())
        }
    }

    private fun placeArrowAtJunction(earth: Earth, target: LatLng, altitude: Double, camLat: Double, camLng: Double) {
        val yaw = bearingDegrees(camLat, camLng, target.lat, target.lng)

        // Convert yaw -> quaternion (y-axis rotation)
        val half = Math.toRadians(yaw / 2.0)
        val qx = 0f
        val qy = sin(half).toFloat()
        val qz = 0f
        val qw = cos(half).toFloat()

        val anchor = earth.createAnchor(
            target.lat,
            target.lng,
            altitude,
            qx, qy, qz, qw
        )

        val anchorNode = AnchorNode(anchor).apply {
            setParent(arSceneView.scene)
        }

        val arrowNode = Node().apply {
            renderable = arrowRenderable
            localScale = Vector3(0.3f, 0.3f, 0.3f)
            setParent(anchorNode)
        }

        arrowAnchorNode = anchorNode
    }

    private fun removeArrow() {
        arrowAnchorNode?.let { node ->
            arSceneView.scene.removeChild(node)
            node.anchor?.detach()
        }
        arrowAnchorNode = null
    }

    private fun fetchRoute(originLat: Double, originLng: Double, destLat: Double, destLng: Double) {
        scope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    fetchDirectionsJunctions(originLat, originLng, destLat, destLng)
                }
                junctions = points
                currentIndex = 0
                tvStatus.text = "Route loaded: ${junctions.size} junctions"
            } catch (e: Exception) {
                tvStatus.text = "Route error: ${e.message}"
                routeRequested = false
            }
        }
    }

    private fun fetchDirectionsJunctions(originLat: Double, originLng: Double, destLat: Double, destLng: Double): List<LatLng> {
        val url =
            "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=$originLat,$originLng" +
                    "&destination=$destLat,$destLng" +
                    "&mode=walking" +
                    "&key=$directionsApiKey"

        val req = Request.Builder().url(url).build()
        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: error("Empty response")

        val json = JSONObject(body)
        val status = json.optString("status")
        if (status != "OK") error("Directions status=$status (${json.optString("error_message")})")

        val steps = json.getJSONArray("routes")
            .getJSONObject(0)
            .getJSONArray("legs")
            .getJSONObject(0)
            .getJSONArray("steps")

        val list = ArrayList<LatLng>(steps.length())
        for (i in 0 until steps.length()) {
            val endLoc = steps.getJSONObject(i).getJSONObject("end_location")
            list.add(LatLng(endLoc.getDouble("lat"), endLoc.getDouble("lng")))
        }
        return dedupeByDistanceMeters(list, 3.0)
    }

    // ---------- AR lifecycle (copy your ScanObject style) ----------
    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                session = Session(this)
                shouldConfigureSession = true
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"; exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"; exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"; exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"; exception = e
            } catch (e: Exception) {
                message = "This device does not support AR"; exception = e
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, "Exception creating session", exception)
                return
            }
        }

        if (shouldConfigureSession) {
            configureSession()
            shouldConfigureSession = false
            arSceneView.session = session
        }

        try {
            session!!.resume()
            arSceneView.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Restart the app.", Toast.LENGTH_LONG).show()
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        arSceneView.pause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        arSceneView.destroy()
        session?.close()
        session = null
    }

    // ---------- helpers ----------
    private fun dedupeByDistanceMeters(points: List<LatLng>, minMeters: Double): List<LatLng> {
        if (points.isEmpty()) return points
        val out = ArrayList<LatLng>()
        var last = points.first()
        out.add(last)
        for (i in 1 until points.size) {
            val p = points[i]
            val d = haversineMeters(last.lat, last.lng, p.lat, p.lng)
            if (d >= minMeters) {
                out.add(p); last = p
            }
        }
        return out
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

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x))
        brng = (brng + 360.0) % 360.0
        return brng
    }
}