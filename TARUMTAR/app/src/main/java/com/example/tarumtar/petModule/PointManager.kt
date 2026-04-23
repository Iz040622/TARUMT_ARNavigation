package com.example.tarumtar.petModule

import android.content.Context
import android.content.SharedPreferences

class PointManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pet_points_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_POINTS = "total_points"
        private const val KEY_UNLOCKED_PETS = "unlocked_pets"
        private const val KEY_SELECTED_PET = "selected_pet"
        
        const val PET_SHIBA = "SHIBA"
        const val PET_HUSKY = "HUSKY"
        
        val PET_COSTS = mapOf(
            PET_SHIBA to 0,
            PET_HUSKY to 100
        )

        val PET_ASSETS = mapOf(
            PET_SHIBA to "miniPets/ShibaInu.glb",
            PET_HUSKY to "miniPets/Husky.glb"
        )
        
        val PET_SCALES = mapOf(
            PET_SHIBA to 0.15f,
            PET_HUSKY to 0.15f
        )
    }

    fun getPoints(): Int {
        return prefs.getInt(KEY_POINTS, 0)
    }

    fun addPoints(points: Int) {
        val current = getPoints()
        prefs.edit().putInt(KEY_POINTS, current + points).apply()
    }

    fun subtractPoints(points: Int): Boolean {
        val current = getPoints()
        if (current >= points) {
            prefs.edit().putInt(KEY_POINTS, current - points).apply()
            return true
        }
        return false
    }

    fun isPetUnlocked(petId: String): Boolean {
        if (petId == PET_SHIBA) return true
        val unlocked = prefs.getStringSet(KEY_UNLOCKED_PETS, setOf(PET_SHIBA)) ?: setOf(PET_SHIBA)
        return unlocked.contains(petId)
    }

    fun unlockPet(petId: String): Boolean {
        val cost = PET_COSTS[petId] ?: return false
        if (subtractPoints(cost)) {
            val unlocked = prefs.getStringSet(KEY_UNLOCKED_PETS, setOf(PET_SHIBA))?.toMutableSet() ?: mutableSetOf(PET_SHIBA)
            unlocked.add(petId)
            prefs.edit().putStringSet(KEY_UNLOCKED_PETS, unlocked).apply()
            return true
        }
        return false
    }

    fun getSelectedPet(): String {
        return prefs.getString(KEY_SELECTED_PET, PET_SHIBA) ?: PET_SHIBA
    }

    fun setSelectedPet(petId: String) {
        prefs.edit().putString(KEY_SELECTED_PET, petId).apply()
    }
}
