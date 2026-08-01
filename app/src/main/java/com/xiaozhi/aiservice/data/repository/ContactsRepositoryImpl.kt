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
import com.xiaozhi.aiservice.data.audio.WakeTemplateMatcher
import com.xiaozhi.aiservice.domain.repository.ContactsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(name = "contacts")

@Singleton
class ContactsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : ContactsRepository {

    companion object {
        private val CONTACTS_KEY = stringPreferencesKey("bot_contacts")
        private val WAKE_TEMPLATES_KEY = stringPreferencesKey("wake_templates")
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

    override suspend fun restoreContactsFromJson(json: String) {
        context.contactsDataStore.edit { preferences ->
            preferences[CONTACTS_KEY] = json
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

            // 同时清理唤醒模板
            val tj = preferences[WAKE_TEMPLATES_KEY] ?: "{}"
            val tt = object : TypeToken<MutableMap<String, String>>() {}.type
            val templates: MutableMap<String, String> = try {
                gson.fromJson(tj, tt) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            templates.remove(contactId)
            preferences[WAKE_TEMPLATES_KEY] = gson.toJson(templates)
        }
    }

    override fun getWakeTemplates(): Flow<Map<String, String>> {
        return context.contactsDataStore.data.map { preferences ->
            val json = preferences[WAKE_TEMPLATES_KEY] ?: "{}"
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    override suspend fun saveWakeTemplate(contactId: String, templateBase64: String) {
        context.contactsDataStore.edit { preferences ->
            val json = preferences[WAKE_TEMPLATES_KEY] ?: "{}"
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            val templates: MutableMap<String, String> = try {
                gson.fromJson(json, type) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            templates[contactId] = templateBase64
            preferences[WAKE_TEMPLATES_KEY] = gson.toJson(templates)
        }
    }

    override suspend fun saveWakeTemplateFromPcm(contactId: String, pcmData: ByteArray) {
        val templateBase64 = WakeTemplateMatcher.extractTemplateFromPcm(pcmData)
        saveWakeTemplate(contactId, templateBase64)
    }

    override suspend fun deleteWakeTemplate(contactId: String) {
        context.contactsDataStore.edit { preferences ->
            val tj = preferences[WAKE_TEMPLATES_KEY] ?: "{}"
            val tt = object : TypeToken<MutableMap<String, String>>() {}.type
            val templates: MutableMap<String, String> = try {
                gson.fromJson(tj, tt) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            templates.remove(contactId)
            preferences[WAKE_TEMPLATES_KEY] = gson.toJson(templates)
        }
    }

}
