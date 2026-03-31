package com.example.tarumtar.navigation

import kotlin.math.*

object pathFinder {

    private fun norm(s: String): String = s.trim().uppercase()

    fun findTop3Paths(
        nodes: List<Node>,
        edges: List<Edge>,
        startId: String,
        endId: String
    ): List<List<Node>> {

        val start = norm(startId)
        val end = norm(endId)

        val nodeMap = nodes.associateBy { norm(it.id) }
        if (!nodeMap.containsKey(start) || !nodeMap.containsKey(end)) return emptyList()

        val adj = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        nodeMap.keys.forEach { adj[it] = mutableListOf() }

        for (e in edges) {
            val from = norm(e.From)
            val to = norm(e.To)
            val w = e.distance

            if (nodeMap.containsKey(from) && nodeMap.containsKey(to)) {
                adj[from]?.add(to to w)
                if (e.bidirectional) {
                    adj[to]?.add(from to w)
                }
            }
        }

        val allPaths = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val currentPath = mutableListOf<String>()

        dfsFindPaths(
            current = start,
            end = end,
            adj = adj,
            visited = visited,
            currentPath = currentPath,
            allPaths = allPaths,
            maxPaths = 50
        )

        val uniquePaths = allPaths
            .distinctBy { it.joinToString("->") }
            .sortedBy { calculatePathDistance(it, adj) }
            .take(3)

        return uniquePaths.map { pathIds ->
            pathIds.mapNotNull { nodeMap[it] }
        }
    }

    private fun dfsFindPaths(
        current: String,
        end: String,
        adj: Map<String, List<Pair<String, Double>>>,
        visited: MutableSet<String>,
        currentPath: MutableList<String>,
        allPaths: MutableList<List<String>>,
        maxPaths: Int
    ) {
        if (allPaths.size >= maxPaths) return

        visited.add(current)
        currentPath.add(current)

        if (current == end) {
            allPaths.add(currentPath.toList())
        } else {
            for ((next, _) in adj[current].orEmpty()) {
                if (next !in visited) {
                    dfsFindPaths(next, end, adj, visited, currentPath, allPaths, maxPaths)
                }
            }
        }

        currentPath.removeAt(currentPath.lastIndex)
        visited.remove(current)
    }

    private fun calculatePathDistance(
        path: List<String>,
        adj: Map<String, List<Pair<String, Double>>>
    ): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) {
            val from = path[i]
            val to = path[i + 1]
            val dist = adj[from]?.firstOrNull { it.first == to }?.second ?: Double.MAX_VALUE
            total += dist
        }
        return total
    }

    fun findPath(
        nodes: List<Node>,
        edges: List<Edge>,
        startId: String,
        endId: String
    ): List<Node> {
        return findTop3Paths(nodes, edges, startId, endId).firstOrNull() ?: emptyList()
    }
}