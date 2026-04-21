package com.example.tarumtar.navigation

data class Edge(
    val From: String = "",
    val To: String = "",
    val distance: Double = 0.0,
    val bidirectional: Boolean = true,
    val isSheltered: Boolean = false
)