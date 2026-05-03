package com.example.ar_control.face

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesFaceEmbeddingStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : FaceEmbeddingStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    override fun loadAll(): List<RememberedFace> {
        val rawJson = preferences.getString(KEY_FACES, null) ?: return emptyList()
        val faces = JSONArray(rawJson)
        return buildList {
            for (index in 0 until faces.length()) {
                add(faces.getJSONObject(index).toRememberedFace())
            }
        }
    }

    override fun remember(
        embedding: FaceEmbedding,
        accessStatus: FaceAccessStatus,
        existingFaceId: String?
    ): RememberedFace {
        val rememberedFaces = loadAll()
        val existingIndex = rememberedFaces.indexOfFirst { face -> face.id == existingFaceId }
        if (existingIndex >= 0) {
            val updatedFace = rememberedFaces[existingIndex].copy(
                embedding = embedding,
                accessStatus = accessStatus
            )
            replaceAll(
                rememberedFaces.toMutableList().also { faces ->
                    faces[existingIndex] = updatedFace
                }
            )
            return updatedFace
        }
        val nextIndex = rememberedFaces.size + 1
        val face = RememberedFace(
            id = "face-$nextIndex",
            label = "Face $nextIndex",
            embedding = embedding,
            accessStatus = accessStatus
        )
        replaceAll(rememberedFaces + face)
        return face
    }

    override fun replaceAll(faces: List<RememberedFace>) {
        val json = JSONArray()
        for (face in faces) {
            json.put(face.toJson())
        }
        preferences.edit()
            .putString(KEY_FACES, json.toString())
            .apply()
    }

    private fun RememberedFace.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("accessStatus", accessStatus.name)
            .put("embedding", JSONArray().also { values ->
                for (value in embedding.values) {
                    values.put(value.toDouble())
                }
            })
    }

    private fun JSONObject.toRememberedFace(): RememberedFace {
        val values = getJSONArray("embedding")
        return RememberedFace(
            id = getString("id"),
            label = getString("label"),
            embedding = FaceEmbedding(FloatArray(values.length()) { index ->
                values.getDouble(index).toFloat()
            }),
            accessStatus = optString("accessStatus", FaceAccessStatus.APPROVED.name)
                .toFaceAccessStatus()
        )
    }

    private fun String.toFaceAccessStatus(): FaceAccessStatus {
        return runCatching { FaceAccessStatus.valueOf(this) }
            .getOrDefault(FaceAccessStatus.APPROVED)
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "face_embeddings"
        const val KEY_FACES = "faces"
    }
}
