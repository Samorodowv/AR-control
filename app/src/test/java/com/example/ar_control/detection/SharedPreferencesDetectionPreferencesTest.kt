package com.example.ar_control.detection

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPreferencesDetectionPreferencesTest {

    private lateinit var preferencesStore: InMemorySharedPreferences
    private lateinit var preferences: SharedPreferencesDetectionPreferences

    @Before
    fun setUp() {
        preferencesStore = InMemorySharedPreferences()
        preferences = SharedPreferencesDetectionPreferences(preferencesStore)
    }

    @Test
    fun defaultsToDisabled() {
        assertFalse(preferences.isObjectDetectionEnabled())
    }

    @Test
    fun persistsUpdatedValue() {
        preferences.setObjectDetectionEnabled(true)

        assertTrue(preferences.isObjectDetectionEnabled())
        assertTrue(preferencesStore.getBoolean("object_detection_enabled", false))
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> {
        return LinkedHashMap(values)
    }

    override fun getString(key: String, defValue: String?): String? {
        return values[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        return (values[key] as? Int) ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        return (values[key] as? Long) ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return (values[key] as? Float) ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return (values[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String): Boolean {
        return values.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return Editor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staged[key] = value
            removals -= key
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            staged[key] = values?.toSet()
            removals -= key
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            staged[key] = value
            removals -= key
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            staged[key] = value
            removals -= key
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            staged[key] = value
            removals -= key
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            staged[key] = value
            removals -= key
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            staged -= key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            staged.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearRequested) {
                values.clear()
                clearRequested = false
            }
            for (key in removals) {
                values.remove(key)
            }
            removals.clear()
            values.putAll(staged)
            staged.clear()
            listeners.forEach { listener ->
                values.keys.forEach { key ->
                    listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                }
            }
        }
    }
}
