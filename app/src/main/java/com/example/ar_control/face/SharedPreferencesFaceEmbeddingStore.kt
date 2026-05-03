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

    override fun remember(embedding: FaceEmbedding): RememberedFace {
        val rememberedFaces = loadAll()
        val nextIndex = rememberedFaces.size + 1
        val face = RememberedFace(
            id = "face-$nextIndex",
            label = "Face $nextIndex",
            embedding = embedding
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
            })
        )
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "face_embeddings"
        const val KEY_FACES = "faces"
    }
}
