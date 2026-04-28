package com.xiaozhi.aiservice.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(name = "contacts")

class ContactsRepositoryImpl(
    private val context: Context,
    private val gson: Gson
) : ContactsRepository {

    companion object {
        private val CONTACTS_KEY = stringPreferencesKey("bot_contacts")
    }

    override fun getContacts(): Flow<List<BotContact>> {
        return context.contactsDataStore.data.map { preferences ->
            val json = preferences[CONTACTS_KEY] ?: "[]"
            try {
                val type = object : TypeToken<List<BotContact>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun saveContact(contact: BotContact) {
        context.contactsDataStore.edit { preferences ->
            val json = preferences[CONTACTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<BotContact>>() {}.type
            val contacts: MutableList<BotContact> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }

            // 如果已存在则更新，否则添加
            val existingIndex = contacts.indexOfFirst { it.id == contact.id }
            if (existingIndex >= 0) {
                contacts[existingIndex] = contact
            } else {
                contacts.add(contact)
            }

            preferences[CONTACTS_KEY] = gson.toJson(contacts)
        }
    }

    override suspend fun deleteContact(contactId: String) {
        context.contactsDataStore.edit { preferences ->
            val json = preferences[CONTACTS_KEY] ?: "[]"
            val type = object : TypeToken<MutableList<BotContact>>() {}.type
            val contacts: MutableList<BotContact> = try {
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
            contacts.removeAll { it.id == contactId }
            preferences[CONTACTS_KEY] = gson.toJson(contacts)
        }
    }
}
