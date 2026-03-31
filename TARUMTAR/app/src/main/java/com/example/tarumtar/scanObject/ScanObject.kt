package com.example.tarumtar.scanObject

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.R
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.rendering.ViewRenderable
import java.io.IOException
import android.widget.TextView
import com.google.ar.sceneform.ArSceneView

class ScanObject : AppCompatActivity() {
    private val TAG = ScanObject::class.java.simpleName
    private var installRequested: Boolean = false
    private var session: Session? = null
    private var shouldConfigureSession = false
    private val messageSnackbarHelper = SnackbarHelper()
    private var isRendered = false
    private lateinit var arSceneView: ArSceneView

    private var infoNode: Node? = null
    private var infoAnchorNode: AnchorNode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scan_object)
        arSceneView = findViewById(R.id.arSceneView)
        initializeSceneView()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    private fun initializeSceneView() {
        arSceneView.scene.addOnUpdateListener(this::onUpdateFrame)
    }

    private fun onUpdateFrame(frameTime: FrameTime) {
        val frame = arSceneView.arFrame ?: return
        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        for (augmentedImage in updatedAugmentedImages) {
            Log.d(TAG, "Update: name=${augmentedImage.name}, state=${augmentedImage.trackingState}")

            if (augmentedImage.trackingState == TrackingState.TRACKING && !isRendered) {
                isRendered = true

                Toast.makeText(this, "DETECTED: ${augmentedImage.name}", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "TRACKING FOUND: ${augmentedImage.name}")

                ViewRenderable.builder()
                    .setView(this, R.layout.layout_bg)
                    .build()
                    .thenAccept { renderable ->
                        // Place it into AR scene
                        onRenderableLoaded(renderable, augmentedImage)
                    }
                    .exceptionally { t ->
                        Log.e(TAG, "Renderable build failed", t)
                        null
                    }
            }
        }
        infoNode?.let { n ->
            val camPos = arSceneView.scene.camera.worldPosition
            val nodePos = n.worldPosition
            val direction = Vector3.subtract(camPos, nodePos)
            n.worldRotation = Quaternion.lookRotation(direction, Vector3.up())
        }
    }

    fun onRenderableLoaded(viewRenderable: ViewRenderable?, augmentedImage: AugmentedImage) {
        if (viewRenderable == null) return

        try {
            val s = session
            if (s == null) {
                Log.e(TAG, "Session is null, cannot create anchor")
                return
            }

            val anchor = s.createAnchor(augmentedImage.centerPose)

            val anchorNode = AnchorNode(anchor)
            anchorNode.setParent(arSceneView.scene) // IMPORTANT

            val node = Node()
            node.renderable = viewRenderable

            // push it a bit towards the camera so it doesn't clip into the image
            node.localPosition = Vector3(0f, 0.08f, 0f)
            node.localScale = Vector3(0.6f, 0.6f, 0.6f)
            node.setParent(anchorNode)

            infoNode = node
            infoAnchorNode = anchorNode

            val message = when (augmentedImage.name) {
                "blockA" -> "You are at\nBlock A"
                "blockB" -> "You are at\nBlock B"
                "blockC" -> "You are at\nBlock C"
                "blockD" -> "You are at\nBlock D"
                else -> "Unknown location"
            }

            setNodeData(viewRenderable, message)

            Log.d(TAG, "Renderable placed successfully for ${augmentedImage.name}")

        } catch (e: Exception) {
            Log.e(TAG, "onRenderableLoaded error", e)
        }
    }

    private fun setupAugmentedImageDb(config: Config): Boolean {
        val s = session ?: return false
        val db = AugmentedImageDatabase(s)

        // Add as many images as you want (name must be unique)
        addImageToDb(db, "blockA", "kitchen.jpeg")
        addImageToDb(db, "blockB", "blockB.png")
        addImageToDb(db, "blockC", "livingroom.jpeg")
        addImageToDb(db, "blockD", "masterroom.jpeg")

        config.augmentedImageDatabase = db
        return true
    }

    private fun addImageToDb(db: AugmentedImageDatabase, name: String, assetFile: String) {
        val bmp = try {
            assets.open(assetFile).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetFile", e)
            null
        }

        if (bmp != null) {
            db.addImage(name, bmp)
            Log.d(TAG, "Added image to DB: $name ($assetFile)")
        }
    }

    private fun loadAugmentedImage(): Bitmap? {
        try {
            assets.open("blockB.jpeg").use { `is` -> return BitmapFactory.decodeStream(`is`) }
        } catch (e: IOException) {
            Log.e(TAG, "IO exception loading augmented image bitmap.", e)
        }

        return null
    }

    private fun configureSession() {
        val config = Config(session)

        config.lightEstimationMode = Config.LightEstimationMode.DISABLED

        config.focusMode = Config.FocusMode.AUTO

        if (!setupAugmentedImageDb(config)) {
            messageSnackbarHelper.showError(this, "Could not setup augmented image database")
        }

        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        session!!.configure(config)
    }

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
                    ArCoreApk.InstallStatus.INSTALLED -> {
                    }
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                session = Session(/* context = */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: Exception) {
                message = "This device does not support AR"
                exception = e
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            shouldConfigureSession = true
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
            messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.")
            session = null
            return
        }
    }

    fun setNodeData(viewRenderable: ViewRenderable, message: String) {
        val view = viewRenderable.view

        val tv = view.findViewById<TextView>(R.id.txtLocation)

        if (tv == null) {
            Log.e(TAG, "TextView txtMessage not found in layout_bg.xml")
            return
        }

        tv.text = message
    }
}