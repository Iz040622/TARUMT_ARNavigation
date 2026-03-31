package com.example.tarumtar.navigation

data class Node(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val visible: Boolean = false,
    val type: String = ""
) {
    fun label(): String = "$id - $name"
}