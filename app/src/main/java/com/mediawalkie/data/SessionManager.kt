package com.mediawalkie.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("session")

class SessionManager(private val context: Context) {
    companion object {
        val USER_NAME = stringPreferencesKey("user_name")
        val IS_VERIFIED = booleanPreferencesKey("is_verified")
    }

    val userNameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USER_NAME]
    }

    val isVerifiedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_VERIFIED] ?: false
    }

    suspend fun saveSession(name: String, isVerified: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USER_NAME] = name
            preferences[IS_VERIFIED] = isVerified
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
