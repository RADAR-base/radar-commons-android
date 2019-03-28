/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.passive.phone

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.ContactsContract
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.PhoneContactList
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class PhoneContactListManager(service: PhoneContactsListService) : AbstractSourceManager<PhoneContactsListService, BaseSourceState>(service) {
    private val preferences: SharedPreferences = service.getSharedPreferences(PhoneContactListManager::class.java.name, Context.MODE_PRIVATE)
    private val contactsTopic: DataCache<ObservationKey, PhoneContactList> = createCache("android_phone_contacts", PhoneContactList())
    private val processor: OfflineProcessor
    private val db: ContentResolver = service.contentResolver
    private lateinit var savedContactLookups: Set<String>

    init {
        processor = OfflineProcessor(service) {
            process = listOf(this@PhoneContactListManager::processContacts)
            requestCode = CONTACTS_LIST_UPDATE_REQUEST_CODE
            requestName = ACTION_UPDATE_CONTACTS_LIST
            wake = false
        }
    }

    override fun start(acceptableIds: Set<String>) {
        updateStatus(SourceStatusListener.Status.READY)
        register()

        processor.start {
            // deprecated using contact _ID, using LOOKUP instead.
            preferences.edit()
                    .remove(CONTACT_IDS)
                    .apply()

            savedContactLookups = preferences.getStringSet(CONTACT_LOOKUPS, emptySet())!!
        }

        updateStatus(SourceStatusListener.Status.CONNECTED)
    }

    private fun queryContacts(): Set<String>? {
        val contactIds = HashSet<String>()

        val limit = 1000
        val sortOrder = "lookup ASC LIMIT $limit"
        var where: String? = null
        var whereArgs: Array<String>? = null

        var numUpdates: Int

        do {
            numUpdates = 0
            lateinit var lastLookup: String
            db.query(ContactsContract.Contacts.CONTENT_URI, LOOKUP_COLUMNS,
                    where, whereArgs, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    numUpdates++
                    lastLookup = cursor.getString(0)
                    contactIds.add(lastLookup)
                }
            } ?: return null

            if (where == null) {
                where = ContactsContract.Contacts.LOOKUP_KEY + " > ?"
                whereArgs = arrayOf(lastLookup)
            } else {
                whereArgs!![0] = lastLookup
            }
        } while (numUpdates == limit && !processor.isDone)

        return contactIds
    }

    @Throws(IOException::class)
    override fun close() {
        processor.close()
        super.close()
    }

    private fun processContacts() {
        val newContactLookups = queryContacts() ?: return

        var added: Int? = null
        var removed: Int? = null

        if (!savedContactLookups.isEmpty()) {
            added = differenceSize(newContactLookups, savedContactLookups)
            removed = differenceSize(savedContactLookups, newContactLookups)
        }

        savedContactLookups = newContactLookups
        preferences.edit()
                .putStringSet(CONTACT_LOOKUPS, savedContactLookups)
                .apply()

        val timestamp = currentTime
        send(contactsTopic, PhoneContactList(timestamp, timestamp, added, removed, newContactLookups.size))
    }

    internal fun setCheckInterval(checkInterval: Long, unit: TimeUnit) {
        processor.interval(checkInterval, unit)
    }

    companion object {
        private const val CONTACTS_LIST_UPDATE_REQUEST_CODE = 15765692
        private const val ACTION_UPDATE_CONTACTS_LIST = "org.radarbase.passive.phone.PhoneContactListManager.ACTION_UPDATE_CONTACTS_LIST"
        private val LOOKUP_COLUMNS = arrayOf(ContactsContract.Contacts.LOOKUP_KEY)
        const val CONTACT_IDS = "contact_ids"
        const val CONTACT_LOOKUPS = "contact_lookups"

        private fun differenceSize(collectionA: Collection<*>, collectionB: Collection<*>): Int {
            return collectionA.count { it !in collectionB }
        }
    }
}
