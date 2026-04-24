package com.example.tarumtar.petModule

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.R
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.gorisse.thomas.sceneform.light.LightEstimationConfig
import com.gorisse.thomas.sceneform.lightEstimationConfig
import com.google.ar.sceneform.Node
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.google.ar.sceneform.math.Quaternion
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.math.abs
import android.view.View
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import com.google.ar.sceneform.SceneView

class petARActivity : AppCompatActivity() {

    private lateinit var arFragment: ArFragment
    private lateinit var txtStatus: TextView

    private val petRenderables = mutableMapOf<PetType, ModelRenderable>()
    private var currentPetType = PetType.SHIBA
    private var petNode: Node? = null
    private var petPlaced = false

    private var isPetReacting = false
    private var petHomePosition = Vector3(0f, 0f, 0f)
    private var petHomeRotation = Quaternion.identity()
    private var lastReaction: PetReaction? = null

    private var ballRenderable: ModelRenderable? = null
    private var bowlRenderable: ModelRenderable? = null

    private var ballNode: Node? = null
    private var bowlNode: Node? = null

    private var petAnchorNode: AnchorNode? = null
    private val dialogSceneViews = mutableListOf<SceneView>()

    private lateinit var fabItems: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var itemMenu: android.widget.LinearLayout
    private lateinit var itemBall: android.widget.ImageView
    private lateinit var itemBowl: android.widget.ImageView

    private var petCenterPosition = Vector3(0f, 0f, 0f)
    private var petCenterRotation = Quaternion.identity()

    private var isItemMenuOpen = false
    private lateinit var fabChangePet: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var pointManager: PointManager
    private enum class PetReaction {
        HAPPY_HOP,
        PLAYFUL_TWIST,
        SHY_BACKSTEP,
        EXCITED_BOUNCE
    }

    private enum class PetType(
        val id: String,
        val displayName: String,
        val assetPath: String,
        val scale: Float,
        val unlockCost: Int
    ) {
        SHIBA(PointManager.PET_SHIBA, "Shiba Inu", "miniPets/ShibaInu.glb", 0.15f, 0),
        HUSKY(PointManager.PET_HUSKY, "Husky", "miniPets/Husky.glb", 0.15f, 100),
    }

    companion object {
        private const val PET_MODEL_YAW_OFFSET = 180f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pet_ar)

        supportActionBar?.hide()

        txtStatus = findViewById(R.id.txtStatus)
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        arFragment.setOnSessionConfigurationListener { _, config ->
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.focusMode = Config.FocusMode.AUTO
        }

        arFragment.setOnViewCreatedListener { arSceneView ->
            arSceneView.lightEstimationConfig = LightEstimationConfig.DISABLED
        }

        fabItems = findViewById(R.id.fabItems)
        itemMenu = findViewById(R.id.itemMenu)
        itemBall = findViewById(R.id.itemBall)
        itemBowl = findViewById(R.id.itemBowl)
        fabChangePet = findViewById(R.id.fabChangePet)

        itemMenu.post {
            itemMenu.visibility = View.INVISIBLE

            itemBall.alpha = 0f
            itemBall.translationX = -30f
            itemBall.scaleX = 0.7f
            itemBall.scaleY = 0.7f

            itemBowl.alpha = 0f
            itemBowl.translationX = -30f
            itemBowl.scaleX = 0.7f
            itemBowl.scaleY = 0.7f
        }

        loadPetModels()
        setupTapToPlace()
        loadItemModels()
        setupItemMenu()
        setupChangePetButton()
        
        pointManager = PointManager(this)
    }

    private fun loadPetModels() {
        txtStatus.text = "Loading pet models..."

        val petTypes = PetType.values()
        var loadedCount = 0

        for (petType in petTypes) {
            ModelRenderable.builder()
                .setSource(this, Uri.parse(petType.assetPath))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept { renderable ->
                    petRenderables[petType] = renderable
                    loadedCount++

                    if (loadedCount == petTypes.size) {
                        txtStatus.text = "Tap on floor to place your pet"
                    }
                }
                .exceptionally { error ->
                    Toast.makeText(
                        this,
                        "Failed to load ${petType.displayName}: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    null
                }
        }
    }

    private fun setupTapToPlace() {
        arFragment.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane, _ ->
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) return@setOnTapArPlaneListener

            if (!petPlaced) {
                val renderable = petRenderables[currentPetType] ?: run {
                    Toast.makeText(this, "${currentPetType.displayName} is still loading", Toast.LENGTH_SHORT).show()
                    return@setOnTapArPlaneListener
                }
                placePet(hitResult, renderable)
            }
        }
    }

    private fun placePet(hitResult: HitResult, renderable: ModelRenderable) {
        val anchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(arFragment.arSceneView.scene)

        val node = Node().apply {
            this.renderable = renderable
            setParent(anchorNode)

            val scale = currentPetType.scale
            localScale = Vector3(scale, scale, scale)
            localPosition = Vector3(0f, 0f, 0f)
            localRotation = Quaternion.identity()

            setOnTapListener { _, _ ->
                playRandomReaction()
            }
        }

        petAnchorNode = anchorNode
        petNode = node
        petHomePosition = node.localPosition
        petHomeRotation = node.localRotation

        petCenterPosition = node.localPosition
        petCenterRotation = node.localRotation

        petPlaced = true
        txtStatus.text = "${currentPetType.displayName} placed."
    }
    private fun playRandomReaction() {
        if (isPetReacting) return

        val choices = enumValues<PetReaction>().filter { it != lastReaction }
        val reaction = choices[Random.nextInt(choices.size)]
        lastReaction = reaction

        when (reaction) {
            PetReaction.HAPPY_HOP -> playHappyHop()
            PetReaction.PLAYFUL_TWIST -> playPlayfulTwist()
            PetReaction.SHY_BACKSTEP -> playShyBackstep()
            PetReaction.EXCITED_BOUNCE -> playExcitedBounce()
        }
    }

    private fun playHappyHop() {
        val node = petNode ?: return
        isPetReacting = true
        txtStatus.text = "Happy hop!"

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val jump =
                    pulse(t, 0.00f, 0.30f) * 0.10f +
                            pulse(t, 0.38f, 0.65f) * 0.06f

                val yaw = sin(t * PI * 4).toFloat() * 10f

                node.localPosition = Vector3(
                    petHomePosition.x,
                    petHomePosition.y + jump,
                    petHomePosition.z
                )

                node.localRotation = Quaternion.multiply(
                    petHomeRotation,
                    Quaternion.axisAngle(Vector3(0f, 1f, 0f), yaw)
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPetPose(node)
                }
            })

            start()
        }
    }

    private fun playPlayfulTwist() {
        val node = petNode ?: return
        isPetReacting = true
        txtStatus.text = "Playful twist!"

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val side = sin(t * PI * 4).toFloat() * 0.03f
                val yaw = sin(t * PI * 6).toFloat() * 22f
                val tilt = sin(t * PI * 6).toFloat() * 8f

                node.localPosition = Vector3(
                    petHomePosition.x + side,
                    petHomePosition.y,
                    petHomePosition.z
                )

                val yawQ = Quaternion.axisAngle(Vector3(0f, 1f, 0f), yaw)
                val tiltQ = Quaternion.axisAngle(Vector3(0f, 0f, 1f), tilt)

                node.localRotation = Quaternion.multiply(
                    petHomeRotation,
                    Quaternion.multiply(yawQ, tiltQ)
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPetPose(node)
                }
            })

            start()
        }
    }

    private fun playShyBackstep() {
        val node = petNode ?: return
        isPetReacting = true
        txtStatus.text = "Shy step!"

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950L
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val back = when {
                    t < 0.45f -> -0.06f * (t / 0.45f)
                    else -> -0.06f * (1f - (t - 0.45f) / 0.55f)
                }

                val tilt = sin(t * PI * 2).toFloat() * -10f
                val headTurn = sin(t * PI * 3).toFloat() * 12f

                node.localPosition = Vector3(
                    petHomePosition.x,
                    petHomePosition.y,
                    petHomePosition.z + back
                )

                val yawQ = Quaternion.axisAngle(Vector3(0f, 1f, 0f), headTurn)
                val tiltQ = Quaternion.axisAngle(Vector3(1f, 0f, 0f), tilt)

                node.localRotation = Quaternion.multiply(
                    petHomeRotation,
                    Quaternion.multiply(yawQ, tiltQ)
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPetPose(node)
                }
            })

            start()
        }
    }

    private fun playExcitedBounce() {
        val node = petNode ?: return
        isPetReacting = true
        txtStatus.text = "Excited bounce!"

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val jump =
                    pulse(t, 0.00f, 0.18f) * 0.06f +
                            pulse(t, 0.22f, 0.40f) * 0.09f +
                            pulse(t, 0.46f, 0.66f) * 0.12f +
                            pulse(t, 0.72f, 0.90f) * 0.07f

                val side = sin(t * PI * 8).toFloat() * 0.02f
                val yaw = sin(t * PI * 10).toFloat() * 18f

                node.localPosition = Vector3(
                    petHomePosition.x + side,
                    petHomePosition.y + jump,
                    petHomePosition.z
                )

                node.localRotation = Quaternion.multiply(
                    petHomeRotation,
                    Quaternion.axisAngle(Vector3(0f, 1f, 0f), yaw)
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetPetPose(node)
                }
            })

            start()
        }
    }

    private fun setupItemMenu() {
        fabItems.setOnClickListener {
            if (isItemMenuOpen) closeItemMenu() else openItemMenu()
        }

        itemBall.setOnClickListener {
            closeItemMenu()

            if (!petPlaced) {
                txtStatus.text = "Place your pet first"
                return@setOnClickListener
            }

            val renderable = ballRenderable ?: run {
                Toast.makeText(this, "Ball model is still loading", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            spawnBallNearPet(renderable)
            onBallTapped()
        }

        itemBowl.setOnClickListener {
            closeItemMenu()

            if (!petPlaced) {
                txtStatus.text = "Place your pet first"
                return@setOnClickListener
            }

            val renderable = bowlRenderable ?: run {
                Toast.makeText(this, "Bowl model is still loading", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            spawnBowlNearPet(renderable)
            onBowlTapped()
        }
    }
    private fun spawnBallNearPet(renderable: ModelRenderable) {
        val root = petAnchorNode ?: return
        val pet = petNode ?: return

        ballNode?.setParent(null)
        ballNode = null

        val (front, side) = getUserViewDirections()

        val spawnWorld = Vector3.add(
            pet.worldPosition,
            Vector3.add(
                front.scaled(0.42f),
                side.scaled(0.12f)
            )
        )

        val node = Node().apply {
            this.renderable = renderable
            setParent(root)

            localScale = Vector3(0.12f, 0.12f, 0.12f)
            worldPosition = Vector3(
                spawnWorld.x,
                pet.worldPosition.y,
                spawnWorld.z
            )

            setOnTapListener { _, _ ->
                onBallTapped()
            }
        }

        ballNode = node
        txtStatus.text = "Ball appeared!"
    }

    private fun spawnBowlNearPet(renderable: ModelRenderable) {
        val root = petAnchorNode ?: return
        val pet = petNode ?: return

        bowlNode?.setParent(null)
        bowlNode = null

        val (front, side) = getUserViewDirections()

        val spawnWorld = Vector3.add(
            pet.worldPosition,
            Vector3.add(
                front.scaled(0.48f),
                side.scaled(-0.14f)
            )
        )

        val node = Node().apply {
            this.renderable = renderable
            setParent(root)

            localScale = Vector3(1.30f, 1.30f, 1.30f)
            worldPosition = Vector3(
                spawnWorld.x,
                pet.worldPosition.y,
                spawnWorld.z
            )

            setOnTapListener { _, _ ->
                onBowlTapped()
            }
        }

        bowlNode = node
        txtStatus.text = "Bowl appeared!"
    }
    private fun onBallTapped() {
        val pet = petNode ?: return
        val ball = ballNode ?: return
        if (isPetReacting) return

        txtStatus.text = "Go get the ball!"

        val target = computeStopBeforeItem(
            pet.worldPosition,
            ball.worldPosition,
            0.25f
        )

        movePetToWorldTarget(target, 1000L, 0.025f) {
            txtStatus.text = "Nice catch!"
            txtStatus.postDelayed({
                removeBall()
                playRandomReaction()

                txtStatus.postDelayed({
                    returnPetToCenter()
                }, 1600)
            }, 300)
        }
    }

    private fun onBowlTapped() {
        val pet = petNode ?: return
        val bowl = bowlNode ?: return
        if (isPetReacting) return

        txtStatus.text = "Time to eat!"

        val target = computeStopBeforeItem(
            pet.worldPosition,
            bowl.worldPosition,
            0.25f
        )

        movePetToWorldTarget(target, 1000L, 0.015f) {
            playEatingReaction()
        }
    }

    private fun playEatingReaction() {
        val pet = petNode ?: return
        if (isPetReacting) return

        isPetReacting = true
        txtStatus.text = "Yum yum..."

        val baseWorldPos = pet.worldPosition
        val baseRotation = pet.localRotation

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val dip = abs(sin(t * PI * 5)).toFloat() * 16f
                val bob = abs(sin(t * PI * 5)).toFloat() * 0.01f

                pet.worldPosition = Vector3(
                    baseWorldPos.x,
                    baseWorldPos.y - bob,
                    baseWorldPos.z
                )

                val tilt = Quaternion.axisAngle(Vector3(1f, 0f, 0f), -dip)
                pet.localRotation = Quaternion.multiply(baseRotation, tilt)
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pet.worldPosition = baseWorldPos
                    pet.localRotation = baseRotation

                    petHomePosition = pet.localPosition
                    petHomeRotation = pet.localRotation

                    isPetReacting = false
                    txtStatus.text = "Finished eating."

                    txtStatus.postDelayed({
                        removeBowl()
                        returnPetToCenter()
                    }, 300)
                }
            })

            start()
        }
    }
    private fun loadItemModels() {
        ModelRenderable.builder()
            .setSource(this, Uri.parse("Item/soccer_ball.glb"))
            .setIsFilamentGltf(true)
            .setAsyncLoadEnabled(true)
            .build()
            .thenAccept { renderable ->
                ballRenderable = renderable
            }
            .exceptionally { error ->
                Toast.makeText(this, "Failed to load soccer_ball.glb: ${error.message}", Toast.LENGTH_LONG).show()
                null
            }

        ModelRenderable.builder()
            .setSource(this, Uri.parse("Item/pet_bowl.glb"))
            .setIsFilamentGltf(true)
            .setAsyncLoadEnabled(true)
            .build()
            .thenAccept { renderable ->
                bowlRenderable = renderable
            }
            .exceptionally { error ->
                Toast.makeText(this, "Failed to load pet_bowl.glb: ${error.message}", Toast.LENGTH_LONG).show()
                null
            }
    }

    private fun movePetToWorldTarget(
        target: Vector3,
        durationMs: Long,
        hopHeight: Float,
        onEnd: () -> Unit
    ) {
        val pet = petNode ?: return
        val start = pet.worldPosition

        isPetReacting = true

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val base = lerpVector3(start, target, t)
                val hop = abs(sin(t * PI * 4)).toFloat() * hopHeight

                pet.worldPosition = Vector3(
                    base.x,
                    base.y + hop,
                    base.z
                )

                val dx = target.x - base.x
                val dz = target.z - base.z
                if (kotlin.math.abs(dx) > 0.0001f || kotlin.math.abs(dz) > 0.0001f) {
                    val direction = Vector3(dx, 0f, dz).normalized()

                    val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
                    val correction = Quaternion.axisAngle(Vector3(0f, 1f, 0f), PET_MODEL_YAW_OFFSET)

                    pet.worldRotation = Quaternion.multiply(lookRotation, correction)
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pet.worldPosition = target

                    petHomePosition = pet.localPosition
                    petHomeRotation = pet.localRotation

                    isPetReacting = false
                    onEnd()
                }
            })

            start()
        }
    }

    private fun returnPetToCenter() {
        val pet = petNode ?: return
        val root = petAnchorNode ?: return
        if (isPetReacting) return

        val start = pet.worldPosition
        val target = Vector3.add(root.worldPosition, petCenterPosition)

        isPetReacting = true
        txtStatus.text = "Coming back..."

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val base = lerpVector3(start, target, t)
                val hop = abs(sin(t * PI * 3)).toFloat() * 0.02f

                pet.worldPosition = Vector3(
                    base.x,
                    base.y + hop,
                    base.z
                )

                val dx = target.x - base.x
                val dz = target.z - base.z
                if (kotlin.math.abs(dx) > 0.0001f || kotlin.math.abs(dz) > 0.0001f) {
                    val direction = Vector3(dx, 0f, dz).normalized()
                    val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
                    val correction = Quaternion.axisAngle(Vector3(0f, 1f, 0f), PET_MODEL_YAW_OFFSET)
                    pet.worldRotation = Quaternion.multiply(lookRotation, correction)
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pet.localPosition = petCenterPosition

                    petHomePosition = petCenterPosition
                    isPetReacting = false

                    facePetTowardUser()
                    txtStatus.text = "Back to center."
                }
            })

            start()
        }
    }

    private fun setupChangePetButton() {
        fabChangePet.setOnClickListener {
            showPetSelectionDialog()
        }
    }

    private fun showPetSelectionDialog() {
        for (sv in dialogSceneViews) {
            sv.pause()
            sv.destroy()
        }
        dialogSceneViews.clear()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_pet_selection)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val container = dialog.findViewById<LinearLayout>(R.id.containerPets)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnCancel)
        
        btnCancel.setOnClickListener { dialog.dismiss() }

        val petTypes = PetType.values()
        for (type in petTypes) {
            val itemView = layoutInflater.inflate(R.layout.item_pet_selection, container, false)
            val txtName = itemView.findViewById<TextView>(R.id.txtPetName)
            val imgLock = itemView.findViewById<ImageView>(R.id.imgLockStatus)
            val previewContainer = itemView.findViewById<FrameLayout>(R.id.containerSceneView)
            
            val isUnlocked = pointManager.isPetUnlocked(type.id)
            txtName.text = if (isUnlocked) type.displayName else "${type.displayName} (Locked)"
            imgLock.visibility = if (isUnlocked) View.GONE else View.VISIBLE
            
            // Add 3D Preview
            val sceneView = SceneView(this)
            dialogSceneViews.add(sceneView)
            previewContainer.addView(sceneView)
            
            ModelRenderable.builder()
                .setSource(this, Uri.parse(type.assetPath))
                .setIsFilamentGltf(true)
                .build()
                .thenAccept { renderable ->
                    val node = Node().apply {
                        setParent(sceneView.scene)
                        this.renderable = renderable
                        val scale = type.scale * 0.5f
                        localScale = Vector3(scale, scale, scale)
                        localPosition = Vector3(0f, -0.1f, -0.4f)
                        localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 180f)
                    }
                    sceneView.scene.addOnUpdateListener {
                        val currentRot = node.localRotation
                        val deltaRot = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 0.8f)
                        node.localRotation = Quaternion.multiply(currentRot, deltaRot)
                    }
                }

            itemView.setOnClickListener {
                if (isUnlocked) {
                    changePet(type)
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "You need to unlock this pet in the Shop first!", Toast.LENGTH_SHORT).show()
                }
            }
            
            container.addView(itemView)
        }

        dialog.setOnDismissListener {
            for (sv in dialogSceneViews) {
                sv.pause()
                sv.destroy()
            }
            dialogSceneViews.clear()
        }

        dialog.show()
        for (sv in dialogSceneViews) sv.resume()
    }

    private fun changePet(newPetType: PetType) {
        if (currentPetType == newPetType) {
            txtStatus.text = "${newPetType.displayName} already selected"
            return
        }

        val newRenderable = petRenderables[newPetType] ?: run {
            Toast.makeText(this, "${newPetType.displayName} is still loading", Toast.LENGTH_SHORT).show()
            return
        }

        currentPetType = newPetType

        val node = petNode
        if (node != null) {
            node.renderable = newRenderable

            val scale = newPetType.scale
            node.localScale = Vector3(scale, scale, scale)

            txtStatus.text = "Changed pet to ${newPetType.displayName}"
        } else {
            txtStatus.text = "${newPetType.displayName} selected. Tap floor to place."
        }
    }

    private fun computeStopBeforeItem(
        petPos: Vector3,
        itemPos: Vector3,
        stopDistance: Float
    ): Vector3 {
        val dx = itemPos.x - petPos.x
        val dz = itemPos.z - petPos.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)

        if (length < 0.0001f) {
            return Vector3(itemPos.x, petPos.y, itemPos.z)
        }

        val nx = dx / length
        val nz = dz / length

        return Vector3(
            itemPos.x - nx * stopDistance,
            petPos.y,
            itemPos.z - nz * stopDistance
        )
    }

    private fun lerpVector3(start: Vector3, end: Vector3, t: Float): Vector3 {
        return Vector3(
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t,
            start.z + (end.z - start.z) * t
        )
    }

    // Helper
    private fun resetPetPose(node: Node) {
        node.localPosition = petHomePosition
        node.localRotation = petHomeRotation
        isPetReacting = false
        txtStatus.text = "Tap the dog again."
    }

    private fun pulse(t: Float, start: Float, end: Float): Float {
        if (t < start || t > end) return 0f
        val phase = (t - start) / (end - start)
        return sin(phase * PI).toFloat()
    }

    private fun removeBall() {
        ballNode?.setParent(null)
        ballNode = null
    }

    private fun removeBowl() {
        bowlNode?.setParent(null)
        bowlNode = null
    }

    private fun getUserViewDirections(): Pair<Vector3, Vector3> {
        val pet = petNode ?: return Pair(Vector3(0f, 0f, -1f), Vector3(1f, 0f, 0f))
        val camera = arFragment.arSceneView.scene.camera

        var front = Vector3.subtract(camera.worldPosition, pet.worldPosition)
        front = Vector3(front.x, 0f, front.z)

        if (front.length() < 0.0001f) {
            front = Vector3(0f, 0f, -1f)
        } else {
            front = front.normalized()
        }

        val side = Vector3(-front.z, 0f, front.x).normalized()
        return Pair(front, side)
    }

    private fun facePetTowardUser() {
        val pet = petNode ?: return
        val camera = arFragment.arSceneView.scene.camera

        var direction = Vector3.subtract(camera.worldPosition, pet.worldPosition)
        direction = Vector3(direction.x, 0f, direction.z)

        if (direction.length() < 0.0001f) return

        direction = direction.normalized()

        val lookRotation = Quaternion.lookRotation(direction, Vector3.up())
        val correction = Quaternion.axisAngle(Vector3(0f, 1f, 0f), PET_MODEL_YAW_OFFSET)

        pet.worldRotation = Quaternion.multiply(lookRotation, correction)

        petHomeRotation = pet.localRotation
    }

    private fun openItemMenu() {
        isItemMenuOpen = true
        itemMenu.visibility = View.VISIBLE

        itemBall.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .start()

        itemBowl.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(60)
            .setDuration(180)
            .start()
    }

    private fun closeItemMenu() {
        isItemMenuOpen = false

        itemBall.animate()
            .alpha(0f)
            .translationX(-30f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(140)
            .start()

        itemBowl.animate()
            .alpha(0f)
            .translationX(-30f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(140)
            .withEndAction {
                itemMenu.visibility = View.INVISIBLE
            }
            .start()
    }
}