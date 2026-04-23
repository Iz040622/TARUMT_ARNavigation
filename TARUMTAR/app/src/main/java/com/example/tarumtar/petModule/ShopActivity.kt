package com.example.tarumtar.petModule

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.tarumtar.R
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.SceneView
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable

class ShopActivity : AppCompatActivity() {

    private lateinit var pointManager: PointManager
    private lateinit var txtTotalPoints: TextView
    private lateinit var containerPets: LinearLayout
    private val sceneViews = mutableListOf<SceneView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop)

        pointManager = PointManager(this)
        txtTotalPoints = findViewById(R.id.txtTotalPoints)
        containerPets = findViewById(R.id.containerPets)

        refreshUI()
    }

    private fun refreshUI() {
        txtTotalPoints.text = pointManager.getPoints().toString()
        
        for (sv in sceneViews) {
            sv.pause()
            sv.destroy()
        }
        sceneViews.clear()
        
        containerPets.removeAllViews()

        val pets = listOf(
            PointManager.PET_SHIBA,
            PointManager.PET_HUSKY
        )

        for (petId in pets) {
            addPetToView(petId)
        }
    }

    private fun addPetToView(petId: String) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_pet_shop, containerPets, false)

        val txtName = view.findViewById<TextView>(R.id.txtPetName)
        val txtCost = view.findViewById<TextView>(R.id.txtPetCost)
        val btnAction = view.findViewById<Button>(R.id.btnAction)

        val cost = PointManager.PET_COSTS[petId] ?: 0
        txtName.text = if (petId == PointManager.PET_SHIBA) "Shiba Inu (Default)" else "Husky"
        txtCost.text = "Cost: $cost points"

        updateButtonState(petId, btnAction)

        // Add 3D Preview
        val container = view.findViewById<FrameLayout>(R.id.containerSceneView)
        val sceneView = SceneView(this)
        sceneViews.add(sceneView)
        container.addView(sceneView)
        
        val assetPath = PointManager.PET_ASSETS[petId]
        if (assetPath != null) {
            ModelRenderable.builder()
                .setSource(this, Uri.parse(assetPath))
                .setIsFilamentGltf(true)
                .build()
                .thenAccept { renderable ->
                    val node = Node().apply {
                        setParent(sceneView.scene)
                        this.renderable = renderable
                        val scale = (PointManager.PET_SCALES[petId] ?: 0.15f) * 1.5f
                        localScale = Vector3(scale, scale, scale)
                        localPosition = Vector3(0f, -0.25f, -0.6f)
                        
                        // Facing forward
                        localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 180f)
                    }
                    
                    // Gentle rotation
                    sceneView.scene.addOnUpdateListener {
                        val currentRot = node.localRotation
                        val deltaRot = Quaternion.axisAngle(Vector3(0f, 1f, 0f), 0.8f)
                        node.localRotation = Quaternion.multiply(currentRot, deltaRot)
                    }
                }
        }

        btnAction.setOnClickListener {
            if (pointManager.isPetUnlocked(petId)) {
                pointManager.setSelectedPet(petId)
                Toast.makeText(this, "Companion changed to ${txtName.text}", Toast.LENGTH_SHORT).show()
                refreshUI()
            } else {
                if (pointManager.unlockPet(petId)) {
                    Toast.makeText(this, "${txtName.text} unlocked!", Toast.LENGTH_SHORT).show()
                    refreshUI()
                } else {
                    Toast.makeText(this, "You need more points to unlock this companion!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        containerPets.addView(view)
    }

    override fun onResume() {
        super.onResume()
        for (sv in sceneViews) sv.resume()
    }

    override fun onPause() {
        super.onPause()
        for (sv in sceneViews) sv.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (sv in sceneViews) {
            sv.pause()
            sv.destroy()
        }
    }

    private fun updateButtonState(petId: String, button: Button) {
        val isUnlocked = pointManager.isPetUnlocked(petId)
        val isSelected = pointManager.getSelectedPet() == petId

        if (isSelected) {
            button.text = "Selected"
            button.isEnabled = false
            button.alpha = 0.5f
        } else if (isUnlocked) {
            button.text = "Select"
            button.isEnabled = true
            button.alpha = 1.0f
        } else {
            button.text = "Unlock"
            button.isEnabled = true
            button.alpha = 1.0f
        }
    }
}
