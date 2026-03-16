package com.scamkill.app.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup

object ContactsHelper {

    /**
     * Check if a phone number exists in the device contacts.
     * Returns true if the number is found, false otherwise.
     * Fails gracefully (returns false) if contacts permission isn't granted.
     */
    fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false

        return try {
            val uri = Uri.withAppendedPath(
                PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the contact display name for a phone number, or null if not found.
     */
    fun getContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null

        return try {
            val uri = Uri.withAppendedPath(
                PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
