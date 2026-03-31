package com.example.tarumtar.navigation

import com.google.firebase.firestore.FirebaseFirestore

class graphRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private fun norm(s: String?): String = (s ?: "").trim().uppercase()

    fun loadGraph(
        onDone: (nodes: List<Node>, edges: List<Edge>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val nodesList = mutableListOf<Node>()
        val edgesList = mutableListOf<Edge>()

        db.collection("Nodes").get()
            .addOnSuccessListener { nodesSnap ->

                // ---- Load Nodes ----
                for (doc in nodesSnap.documents) {
                    nodesList.add(
                        Node(
                            id = norm(doc.id), // normalize doc id
                            name = doc.getString("name") ?: "",
                            lat = doc.getDouble("lat") ?: 0.0,
                            lng = doc.getDouble("lng") ?: (doc.getDouble("lon") ?: 0.0),
                            visible = doc.getBoolean("visible") ?: false,
                            type = doc.getString("type") ?: ""
                        )
                    )
                }

                // ---- Load Edges ----
                db.collection("edges").get()
                    .addOnSuccessListener { edgesSnap ->
                        for (doc in edgesSnap.documents) {
                            edgesList.add(
                                Edge(
                                    From = norm(doc.getString("From")),
                                    To = norm(doc.getString("To")),
                                    distance = (doc.get("distance") as? Number)?.toDouble() ?: 0.0,
                                    bidirectional = doc.getBoolean("bidirectional") ?: true
                                )
                            )
                        }
                        onDone(nodesList, edgesList)
                    }
                    .addOnFailureListener { onError(it) }
            }
            .addOnFailureListener { onError(it) }
    }
}