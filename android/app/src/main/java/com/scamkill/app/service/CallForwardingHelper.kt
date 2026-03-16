package com.scamkill.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages GSM conditional call forwarding via USSD/MMI codes.
 *
 * Uses individual supplementary-service codes for maximum carrier compatibility:
 *   Forward on busy:        **67*<number>#   /  ##67#
 *   Forward on no answer:   **61*<number>#   /  ##61#
 *   Forward on unreachable: **62*<number>#   /  ##62#
 *   Check forward-on-busy:  *#67#
 */
object CallForwardingHelper {

    private const val TAG = "ScamStop.CallFwd"
    private const val USSD_TIMEOUT_MS = 30_000L

    sealed class UssdResult {
        data class Success(val response: String) : UssdResult()
        data class Failed(val error: String) : UssdResult()
        object Unsupported : UssdResult()
    }

    /** Human-readable dial code for the user to dial manually. */
    fun activateCode(number: String): String = "**67*${number}#"
    fun deactivateCode(): String = "##67#"
    fun checkCode(): String = "*#67#"

    /**
     * Activate forward-on-busy to [number] via sendUssdRequest.
     */
    suspend fun activate(context: Context, number: String): UssdResult {
        Log.i(TAG, "Activating forward-on-busy to $number")
        return sendUssd(context, "**67*${number}#")
    }

    /**
     * Deactivate forward-on-busy.
     */
    suspend fun deactivate(context: Context): UssdResult {
        Log.i(TAG, "Deactivating forward-on-busy")
        return sendUssd(context, "##67#")
    }

    /**
     * Query forward-on-busy status.
     */
    suspend fun checkStatus(context: Context): UssdResult {
        Log.i(TAG, "Checking forward-on-busy status")
        return sendUssd(context, "*#67#")
    }

    /**
     * Build an ACTION_CALL intent for a MMI code.
     * In tel: URIs, `*` is kept literal and `#` is encoded as `%23`.
     */
    fun buildActivateIntent(number: String): Intent =
        buildMmiCallIntent("**67*${number}#")

    fun buildDeactivateIntent(): Intent =
        buildMmiCallIntent("##67#")

    fun buildCheckStatusIntent(): Intent =
        buildMmiCallIntent("*#67#")

    private fun buildMmiCallIntent(code: String): Intent {
        // In tel: URIs, '#' must be %23 (it's the fragment delimiter).
        // '*' and '+' are valid and must NOT be encoded.
        val uri = "tel:" + code.replace("#", "%23")
        Log.i(TAG, "Built MMI intent: $uri")
        return Intent(Intent.ACTION_CALL, Uri.parse(uri))
    }

    private suspend fun sendUssd(context: Context, code: String): UssdResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "sendUssdRequest requires API 26+, device is ${Build.VERSION.SDK_INT}")
            return UssdResult.Unsupported
        }

        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm == null) {
            Log.e(TAG, "TelephonyManager not available")
            return UssdResult.Unsupported
        }

        Log.i(TAG, "Sending USSD: $code")

        return try {
            val result = withTimeoutOrNull(USSD_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    tm.sendUssdRequest(
                        code,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(
                                telephonyManager: TelephonyManager,
                                request: String,
                                response: CharSequence,
                            ) {
                                Log.i(TAG, "USSD OK for $request: $response")
                                if (cont.isActive) cont.resume(UssdResult.Success(response.toString()))
                            }

                            override fun onReceiveUssdResponseFailed(
                                telephonyManager: TelephonyManager,
                                request: String,
                                failureCode: Int,
                            ) {
                                Log.w(TAG, "USSD FAILED for $request, failureCode=$failureCode")
                                if (cont.isActive) cont.resume(UssdResult.Failed("USSD error code $failureCode"))
                            }
                        },
                        handler,
                    )
                }
            }

            if (result == null) {
                Log.w(TAG, "USSD timed out after ${USSD_TIMEOUT_MS}ms")
                UssdResult.Failed("Timed out waiting for carrier response")
            } else {
                result
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission not granted", e)
            UssdResult.Failed("Permission denied – grant CALL_PHONE")
        } catch (e: Exception) {
            Log.e(TAG, "sendUssdRequest threw", e)
            UssdResult.Failed(e.message ?: "Unknown error")
        }
    }
}
