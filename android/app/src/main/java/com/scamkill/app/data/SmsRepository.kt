package com.scamkill.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.util.Log
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val address: String,
    val displayName: String,
    val lastBody: String,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val messageCount: Int,
)

@Serializable
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
    val read: Boolean,
    val isMms: Boolean = false,
    val mmsImageUri: String? = null,
)

class SmsRepository(private val context: Context) {

    private val sixMonthsMs: Long
        get() = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000)

    /**
     * Load conversations grouped by thread_id.
     * The "other party" address is determined from incoming messages in
     * each thread (since sent messages may store the user's own number
     * on some devices).
     */
    fun getConversations(): List<Conversation> {
        // thread_id -> list of messages
        val threadMap = LinkedHashMap<Long, MutableList<SmsMessage>>()
        // thread_id -> best "other party" address
        val threadAddress = HashMap<Long, String>()

        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ,
                    Telephony.Sms.THREAD_ID,
                ),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(sixMonthsMs.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex(Telephony.Sms._ID)
                val idxAddr = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val idxBody = cursor.getColumnIndex(Telephony.Sms.BODY)
                val idxDate = cursor.getColumnIndex(Telephony.Sms.DATE)
                val idxType = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val idxRead = cursor.getColumnIndex(Telephony.Sms.READ)
                val idxThread = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)

                while (cursor.moveToNext()) {
                    val rawAddr = cursor.getString(idxAddr) ?: ""
                    if (rawAddr.isBlank()) continue
                    val addr = normalizeAddress(rawAddr)
                    if (addr.isBlank()) continue
                    val threadId = cursor.getLong(idxThread)
                    if (threadId <= 0) continue
                    val type = cursor.getInt(idxType)

                    val msg = SmsMessage(
                        id = cursor.getLong(idxId),
                        address = addr,
                        body = cursor.getString(idxBody) ?: "",
                        timestamp = cursor.getLong(idxDate),
                        type = type,
                        read = cursor.getInt(idxRead) == 1,
                    )
                    threadMap.getOrPut(threadId) { mutableListOf() }.add(msg)

                    // Prefer address from incoming messages to identify the other party
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                        threadAddress[threadId] = addr
                    } else if (!threadAddress.containsKey(threadId)) {
                        threadAddress[threadId] = addr
                    }
                }
            }

            readMmsConversations(threadMap, threadAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read SMS", e)
        }

        // Build conversations keyed by canonical address
        val convMap = LinkedHashMap<String, MutableList<SmsMessage>>()
        for ((threadId, msgs) in threadMap) {
            val addr = threadAddress[threadId] ?: continue
            convMap.getOrPut(addr) { mutableListOf() }.addAll(msgs)
        }

        return convMap.map { (addr, msgs) ->
            msgs.sortByDescending { it.timestamp }
            val latest = msgs.first()
            val name = ContactsHelper.getContactName(context, addr) ?: addr
            Conversation(
                address = addr,
                displayName = name.ifBlank { addr.ifBlank { "Unknown" } },
                lastBody = latest.body.ifBlank { "(MMS)" },
                lastTimestamp = latest.timestamp,
                unreadCount = msgs.count { !it.read && isIncoming(it.type) },
                messageCount = msgs.size,
            )
        }.sortedByDescending { it.lastTimestamp }
    }

    /**
     * Load all messages for a conversation identified by [address].
     * Matches by thread_id or address suffix to catch both sent and received.
     */
    fun getMessagesForAddress(address: String): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val normalized = normalizeAddress(address)
        val suffix = normalized.takeLast(9)

        // First, find the thread_id(s) for this address
        val threadIds = mutableSetOf<Long>()

        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(sixMonthsMs.toString()),
                null
            )?.use { cursor ->
                val idxThread = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                val idxAddr = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                while (cursor.moveToNext()) {
                    val rawAddr = cursor.getString(idxAddr) ?: ""
                    val addr = normalizeAddress(rawAddr)
                    if (addr.endsWith(suffix) || normalized.endsWith(addr.takeLast(9))) {
                        val tid = cursor.getLong(idxThread)
                        if (tid > 0) threadIds.add(tid)
                    }
                }
            }

            // Now load all messages from those threads
            if (threadIds.isNotEmpty()) {
                val placeholders = threadIds.joinToString(",") { "?" }
                val args = threadIds.map { it.toString() }.toTypedArray()

                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID,
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.READ,
                    ),
                    "${Telephony.Sms.THREAD_ID} IN ($placeholders) AND ${Telephony.Sms.DATE} > ?",
                    args + sixMonthsMs.toString(),
                    "${Telephony.Sms.DATE} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        messages.add(
                            SmsMessage(
                                id = cursor.getLong(0),
                                address = normalizeAddress(cursor.getString(1) ?: ""),
                                body = cursor.getString(2) ?: "",
                                timestamp = cursor.getLong(3),
                                type = cursor.getInt(4),
                                read = cursor.getInt(5) == 1,
                            )
                        )
                    }
                }
            }

            readMmsForAddress(normalized, messages)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read messages for $address", e)
        }

        return messages.sortedByDescending { it.timestamp }
    }

    fun markAsRead(address: String) {
        try {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.ADDRESS} LIKE ? AND ${Telephony.Sms.READ} = 0",
                arrayOf("%${address.takeLast(10)}%")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark as read", e)
        }
    }

    @Suppress("DEPRECATION")
    fun sendSms(address: String, body: String) {
        val smsManager = android.telephony.SmsManager.getDefault()
        val parts = smsManager.divideMessage(body)
        smsManager.sendMultipartTextMessage(address, null, parts, null, null)

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    }

    // ── MMS helpers ──────────────────────────────────────────────────────────

    private fun readMmsConversations(
        threadMap: LinkedHashMap<Long, MutableList<SmsMessage>>,
        threadAddress: HashMap<Long, String>,
    ) {
        try {
            val mmsUri = Uri.parse("content://mms")
            val cutoffSec = sixMonthsMs / 1000
            context.contentResolver.query(
                mmsUri,
                arrayOf("_id", "date", "read", "msg_box", "thread_id"),
                "date > ?",
                arrayOf(cutoffSec.toString()),
                "date DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(0)
                    val date = cursor.getLong(1) * 1000
                    val read = cursor.getInt(2) == 1
                    val msgBox = cursor.getInt(3)
                    val threadId = cursor.getLong(4)

                    val addr = getMmsAddress(mmsId) ?: continue
                    val normalized = normalizeAddress(addr)
                    val body = getMmsText(mmsId)
                    val imageUri = getMmsImageUri(mmsId)

                    val type = if (msgBox == 1) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT

                    val msg = SmsMessage(
                        id = mmsId,
                        address = normalized,
                        body = body,
                        timestamp = date,
                        type = type,
                        read = read,
                        isMms = true,
                        mmsImageUri = imageUri,
                    )

                    if (threadId > 0) {
                        threadMap.getOrPut(threadId) { mutableListOf() }.add(msg)
                        if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                            threadAddress[threadId] = normalized
                        } else if (!threadAddress.containsKey(threadId)) {
                            threadAddress[threadId] = normalized
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MMS conversations", e)
        }
    }

    private fun readMmsForAddress(normalized: String, messages: MutableList<SmsMessage>) {
        val suffix = normalized.takeLast(9)
        try {
            val mmsUri = Uri.parse("content://mms")
            val cutoffSec = sixMonthsMs / 1000
            context.contentResolver.query(
                mmsUri,
                arrayOf("_id", "date", "read", "msg_box"),
                "date > ?",
                arrayOf(cutoffSec.toString()),
                "date DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(0)
                    val date = cursor.getLong(1) * 1000
                    val read = cursor.getInt(2) == 1
                    val msgBox = cursor.getInt(3)

                    val addr = getMmsAddress(mmsId) ?: continue
                    val norm = normalizeAddress(addr)
                    if (!norm.endsWith(suffix) && !normalized.endsWith(norm.takeLast(9))) continue

                    val body = getMmsText(mmsId)
                    val imageUri = getMmsImageUri(mmsId)

                    messages.add(
                        SmsMessage(
                            id = mmsId,
                            address = norm,
                            body = body,
                            timestamp = date,
                            type = if (msgBox == 1) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT,
                            read = read,
                            isMms = true,
                            mmsImageUri = imageUri,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MMS for address", e)
        }
    }

    fun getMmsAddress(mmsId: Long): String? {
        try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0)
                    val type = cursor.getInt(1)
                    // type 137 = from, 151 = to
                    if (type == 137 && !address.isNullOrBlank() && address != "insert-address-token") {
                        return address
                    }
                }
                cursor.moveToFirst()
                while (!cursor.isAfterLast) {
                    val address = cursor.getString(0)
                    if (!address.isNullOrBlank() && address != "insert-address-token") {
                        return address
                    }
                    cursor.moveToNext()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MMS address", e)
        }
        return null
    }

    fun getMmsText(mmsId: Long): String {
        val sb = StringBuilder()
        try {
            val uri = Uri.parse("content://mms/$mmsId/part")
            context.contentResolver.query(
                uri,
                arrayOf("_id", "ct", "text"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(1) ?: ""
                    if (ct == "text/plain") {
                        val text = cursor.getString(2)
                        if (!text.isNullOrBlank()) {
                            sb.append(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MMS text", e)
        }
        return sb.toString()
    }

    fun getMmsImageUri(mmsId: Long): String? {
        try {
            val uri = Uri.parse("content://mms/$mmsId/part")
            context.contentResolver.query(
                uri,
                arrayOf("_id", "ct"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(0)
                    val ct = cursor.getString(1) ?: ""
                    if (ct.startsWith("image/")) {
                        return "content://mms/part/$partId"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get MMS image", e)
        }
        return null
    }

    fun getMmsImageBase64(mmsId: Long): Pair<String, String>? {
        try {
            val partUri = Uri.parse("content://mms/$mmsId/part")
            context.contentResolver.query(
                partUri,
                arrayOf("_id", "ct"),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(0)
                    val ct = cursor.getString(1) ?: ""
                    if (ct.startsWith("image/")) {
                        val imgUri = Uri.parse("content://mms/part/$partId")
                        context.contentResolver.openInputStream(imgUri)?.use { stream ->
                            val bytes = stream.readBytes()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            return Pair(base64, ct)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MMS image data", e)
        }
        return null
    }

    private fun isIncoming(type: Int): Boolean {
        return type == Telephony.Sms.MESSAGE_TYPE_INBOX
    }

    private fun normalizeAddress(address: String): String {
        val digits = address.replace(Regex("[^+\\d]"), "")
        return digits.ifBlank { address }
    }

    companion object {
        private const val TAG = "ScamStop.SmsRepo"
    }
}
