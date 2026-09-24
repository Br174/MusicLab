/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.music.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * In-memory mirror of the Preferences DataStore.
 *
 * The legacy synchronous getter is used by a large amount of old UI/service code. Once
 * the snapshot collector has emitted, reads are simple in-memory lookups. Before that
 * first emission we must never wait for DataStore disk I/O from Android's main thread:
 * doing so can stall input dispatch long enough for the system ANR dialog to appear.
 *
 * Background callers retain the old blocking fallback so one-shot worker/service reads
 * still obtain the persisted value. Main-thread callers temporarily receive null/default;
 * Compose preference readers are backed by the DataStore flow and update as soon as the
 * first real preferences snapshot arrives.
 */
@Volatile
private var prefsSnapshot: Preferences? = null

fun installPreferencesSnapshotCollector(
    scope: CoroutineScope,
    dataStore: DataStore<Preferences>,
) {
    scope.launch(Dispatchers.IO) {
        dataStore.data.collect { prefsSnapshot = it }
    }
}

private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? {
    prefsSnapshot?.let { return it[key] }
    if (isMainThread()) return null
    return runBlocking(Dispatchers.IO) {
        data.first()[key]
    }
}

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T {
    prefsSnapshot?.let { return it[key] ?: defaultValue }
    if (isMainThread()) return defaultValue
    return runBlocking(Dispatchers.IO) {
        data.first()[key] ?: defaultValue
    }
}

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsState(context.dataStore[key] ?: defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val initialValue = context.dataStore[key].toEnum(defaultValue = defaultValue)
    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsState(initialValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
