/**
 * Audio transcoding: Twilio mulaw 8kHz -> PCM 16kHz for Nova Sonic
 *
 * Twilio Media Streams send audio as:
 *   - Encoding: audio/x-mulaw
 *   - Sample rate: 8000 Hz
 *   - Channels: 1 (mono)
 *   - Payload: base64 encoded
 *
 * Nova 2 Sonic expects:
 *   - Encoding: audio/lpcm (linear PCM)
 *   - Sample rate: 16000 Hz
 *   - Sample size: 16-bit signed
 *   - Channels: 1 (mono)
 *   - Payload: base64 encoded
 */

// µ-law to 16-bit linear PCM decode table (ITU-T G.711)
const MULAW_DECODE_TABLE = new Int16Array(256);
(function buildTable() {
  const MULAW_BIAS = 33;
  const MULAW_MAX = 0x1FFF;
  for (let i = 0; i < 256; i++) {
    let mu = ~i & 0xFF;
    let sign = mu & 0x80;
    let exponent = (mu >> 4) & 0x07;
    let mantissa = mu & 0x0F;
    let sample = ((mantissa << 3) + MULAW_BIAS) << exponent;
    sample -= MULAW_BIAS;
    if (sample > MULAW_MAX) sample = MULAW_MAX;
    MULAW_DECODE_TABLE[i] = sign ? -sample : sample;
  }
})();

/**
 * Decode mulaw bytes to 16-bit PCM samples
 * @param {Buffer} mulawBuf - Raw mulaw bytes
 * @returns {Int16Array} PCM 16-bit samples at 8kHz
 */
function mulawToPcm(mulawBuf) {
  const pcm = new Int16Array(mulawBuf.length);
  for (let i = 0; i < mulawBuf.length; i++) {
    pcm[i] = MULAW_DECODE_TABLE[mulawBuf[i]];
  }
  return pcm;
}

/**
 * Upsample PCM from 8kHz to 16kHz using linear interpolation
 * @param {Int16Array} samples8k - PCM samples at 8kHz
 * @returns {Int16Array} PCM samples at 16kHz
 */
function upsample8to16(samples8k) {
  const len = samples8k.length;
  const samples16k = new Int16Array(len * 2);
  for (let i = 0; i < len - 1; i++) {
    samples16k[i * 2] = samples8k[i];
    samples16k[i * 2 + 1] = (samples8k[i] + samples8k[i + 1]) >> 1;
  }
  // Last sample: duplicate
  samples16k[(len - 1) * 2] = samples8k[len - 1];
  samples16k[(len - 1) * 2 + 1] = samples8k[len - 1];
  return samples16k;
}

/**
 * Convert Int16Array to Buffer (little-endian)
 * @param {Int16Array} samples
 * @returns {Buffer}
 */
function int16ToBuffer(samples) {
  const buf = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) {
    buf.writeInt16LE(samples[i], i * 2);
  }
  return buf;
}

/**
 * Full pipeline: Twilio mulaw base64 -> PCM 16kHz base64
 * @param {string} mulawBase64 - Base64-encoded mulaw audio from Twilio
 * @returns {string} Base64-encoded PCM 16kHz audio for Nova Sonic
 */
function twilioToSonic(mulawBase64) {
  const mulawBuf = Buffer.from(mulawBase64, 'base64');
  const pcm8k = mulawToPcm(mulawBuf);
  const pcm16k = upsample8to16(pcm8k);
  const pcmBuf = int16ToBuffer(pcm16k);
  return pcmBuf.toString('base64');
}

module.exports = { mulawToPcm, upsample8to16, int16ToBuffer, twilioToSonic };
