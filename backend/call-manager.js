/**
 * Twilio call and message management
 */

const twilio = require('twilio');

class CallManager {
  constructor(config) {
    this.config = config;
    this.client = twilio(config.twilio.accountSid, config.twilio.authToken);
  }

  /**
   * Generate TwiML to forward an SMS/MMS to the recipient.
   * Prefixes the body with the original sender since Twilio can't spoof caller ID on SMS.
   */
  generateForwardSmsTwiml(senderNumber, userNumber, body, mediaUrls = []) {
    const response = new twilio.twiml.MessagingResponse();
    const forwardBody = `From ${senderNumber}:\n${body}`;
    const msg = response.message({ to: userNumber }, forwardBody);
    for (const url of mediaUrls) {
      msg.media(url);
    }
    return response.toString();
  }

  /**
   * Generate empty TwiML response (blocks the message silently).
   */
  generateBlockSmsTwiml() {
    const response = new twilio.twiml.MessagingResponse();
    return response.toString();
  }

  /**
   * Send an SMS via Twilio REST API (for reply forwarding).
   */
  async sendSms(to, body, mediaUrls = []) {
    try {
      const params = {
        from: this.config.twilio.phoneNumber,
        to,
        body,
      };
      if (mediaUrls.length > 0) {
        params.mediaUrl = mediaUrls;
      }
      const msg = await this.client.messages.create(params);
      console.log(`[CallManager] SMS sent to ${to}: ${msg.sid}`);
      return msg;
    } catch (err) {
      console.error(`[CallManager] SMS send failed to ${to}:`, err.message);
      return null;
    }
  }

  /**
   * Generate TwiML for incoming call:
   * - Start a background audio stream to our WebSocket
   * - Immediately dial the user's phone number
   *
   * @param {string} wsUrl - WebSocket URL for the media stream
   * @param {string} userNumber - Phone number to dial
   * @param {string} callSid - The call SID for custom parameters
   * @returns {string} TwiML XML
   */
  generateCallTwiml(wsUrl, userNumber, callSid, callerNumber) {
    const response = new twilio.twiml.VoiceResponse();

    // Fork audio to our monitoring WebSocket (runs in background)
    const start = response.start();
    start.stream({
      url: wsUrl,
      track: 'both_tracks',
      name: 'scamstop-monitor',
      statusCallback: `${this.config.server.baseUrl}/stream/status`,
      statusCallbackMethod: 'POST',
    });

    // Pass through the original caller's number so the recipient sees who's calling
    response.dial(
      {
        callerId: callerNumber || this.config.twilio.phoneNumber,
        timeout: 30,
        action: `${this.config.server.baseUrl}/voice/status`,
        method: 'POST',
      },
      userNumber
    );

    return response.toString();
  }

  /**
   * Terminate a live call and play a warning message
   * @param {string} callSid - The Twilio Call SID
   * @param {string} reason - Reason for termination
   */
  async terminateCall(callSid, reason = 'Scam activity detected') {
    try {
      const safeReason = reason
        .replace(/&/g, 'and')
        .replace(/[<>"']/g, ' ')
        .trim();
      const warningTwiml = `<Response>
        <Say voice="Polly.Joanna">
          Warning. This call has been terminated by your ScamStop guardian.
          A possible scam attempt was detected. ${safeReason}.
          Please hang up and do not call back.
        </Say>
        <Hangup/>
      </Response>`;

      // Find the child call leg (outbound to the protected person)
      const childCalls = await this.client.calls.list({ parentCallSid: callSid, limit: 5 });

      if (childCalls.length > 0) {
        // Play warning to the recipient's leg (they hear it)
        await this.client.calls(childCalls[0].sid).update({ twiml: warningTwiml });
        // Also inject into parent so the recording captures the warning, then it self-hangs
        await this.client.calls(callSid).update({ twiml: warningTwiml });
      } else {
        // Fallback: no child leg found, update parent directly
        await this.client.calls(callSid).update({ twiml: warningTwiml });
      }

      console.log(`[CallManager] Terminated call ${callSid}: ${reason}`);
      return true;
    } catch (err) {
      console.error(`[CallManager] Failed to terminate ${callSid}:`, err.message);
      return false;
    }
  }

  /**
   * Get info about a call
   * @param {string} callSid
   */
  async getCallInfo(callSid) {
    try {
      return await this.client.calls(callSid).fetch();
    } catch (err) {
      console.error(`[CallManager] Failed to fetch ${callSid}:`, err.message);
      return null;
    }
  }
}

module.exports = { CallManager };
