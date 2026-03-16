/**
 * SMS/MMS scam detection via Nova 2 Lite.
 *
 * Stateless single-shot analysis: takes a message body (and optional
 * image URLs), sends to Nova 2 Lite via the Converse API, and returns
 * a score/verdict/reason/keywords object.
 */

const https = require('https');
const {
  BedrockRuntimeClient,
  ConverseCommand,
} = require('@aws-sdk/client-bedrock-runtime');
const { fromIni } = require('@aws-sdk/credential-providers');

const SYSTEM_PROMPT = `You are a fraud detection AI screening SMS and MMS messages sent to a recipient who is vulnerable to scams.

Analyze the message for scam indicators:
- Phishing links or suspicious URLs
- Urgency/pressure tactics ("act now", "limited time", "account suspended")
- Impersonation of banks, government agencies, delivery services, tech companies
- Requests for personal information (SSN, credit card, bank account, passwords, PINs)
- Gift card or wire transfer requests
- Prize/lottery/inheritance scams ("you've won", "claim your reward")
- Package delivery scams ("your package is held", "confirm delivery")
- Emotional manipulation or threats
- "Click here" or shortened/obfuscated URLs
- Requests to call back suspicious numbers
- Romance or advance-fee fraud patterns
- If images are included, check for fake logos, forged documents, or QR codes leading to phishing sites

You MUST respond with ONLY a valid JSON object, no other text:
{
  "score": <0-100 integer, 0=completely safe, 100=definite scam>,
  "verdict": "<SAFE|SUSPICIOUS|LIKELY_SCAM|SCAM>",
  "reason": "<brief 1-sentence explanation>",
  "keywords": ["<detected scam keywords or phrases>"]
}

Scoring guide:
- 0-20: SAFE - Normal personal message, no red flags
- 21-50: SUSPICIOUS - Some unusual elements but inconclusive
- 51-79: LIKELY_SCAM - Multiple scam indicators present
- 80-100: SCAM - Clear fraudulent intent detected`;

class SmsAnalyzer {
  constructor(config) {
    this.config = config;
    this.client = new BedrockRuntimeClient({
      region: config.aws.region,
      credentials: fromIni({ profile: config.aws.profile }),
    });
  }

  /**
   * Analyze an SMS/MMS message for scam content.
   * @param {string} body - The text body of the message
   * @param {string} from - The sender's phone number
   * @param {Array<{url: string, contentType: string}>} media - MMS attachments
   * @returns {Promise<{score, verdict, reason, keywords}>}
   */
  async analyze(body, from, media = []) {
    const contentBlocks = [];

    // Add any MMS images to the content blocks
    for (const item of media) {
      if (item.contentType && item.contentType.startsWith('image/')) {
        try {
          let imageData;
          if (item.data) {
            // Inline base64 data (from Android app)
            imageData = item.data;
          } else if (item.url) {
            // URL-based (from Twilio webhook)
            imageData = await this._fetchImageAsBase64(item.url);
          }
          if (imageData) {
            const format = item.contentType.replace('image/', '').replace('jpg', 'jpeg');
            contentBlocks.push({
              image: {
                format,
                source: { bytes: Buffer.from(imageData, 'base64') },
              },
            });
          }
        } catch (err) {
          console.error('[SmsAnalyzer] Failed to process MMS image:', err.message);
        }
      }
    }

    const hasImages = contentBlocks.some(b => b.image);
    const prompt = hasImages
      ? `Analyze this MMS message for fraud. IMPORTANT: Carefully read and analyze ALL text visible in the attached image(s) — scam content is often embedded in images while the message text is kept innocent to evade detection. If the image contains any scam indicators, the overall verdict MUST reflect that regardless of how innocent the message text appears.\n\nFrom: ${from}\nMessage text: ${body || '(no text - image only)'}`
      : `Analyze this SMS message for fraud.\n\nFrom: ${from}\nMessage: ${body || '(no text)'}`;
    contentBlocks.push({ text: prompt });

    try {
      const command = new ConverseCommand({
        modelId: this.config.models.lite,
        system: [{ text: SYSTEM_PROMPT }],
        messages: [
          {
            role: 'user',
            content: contentBlocks,
          },
        ],
        inferenceConfig: {
          maxTokens: 256,
          temperature: 0.2,
        },
      });

      const response = await this.client.send(command);
      const text = response.output?.message?.content?.[0]?.text || '';

      const jsonMatch = text.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        const result = JSON.parse(jsonMatch[0]);
        return {
          score: Math.max(0, Math.min(100, parseInt(result.score) || 0)),
          verdict: result.verdict || 'UNKNOWN',
          reason: result.reason || '',
          keywords: result.keywords || [],
        };
      }

      console.error('[SmsAnalyzer] No JSON in response:', text.slice(0, 200));
      return { score: 0, verdict: 'UNKNOWN', reason: 'Analysis failed to parse', keywords: [] };
    } catch (err) {
      console.error('[SmsAnalyzer] Error:', err.message);
      return { score: 0, verdict: 'ERROR', reason: err.message, keywords: [] };
    }
  }

  /**
   * Fetch an image URL and return base64-encoded data.
   * Twilio MMS media URLs require Basic auth with account SID + auth token.
   */
  _fetchImageAsBase64(url) {
    return new Promise((resolve, reject) => {
      const auth = Buffer.from(
        `${this.config.twilio.accountSid}:${this.config.twilio.authToken}`
      ).toString('base64');

      const options = {
        headers: { Authorization: `Basic ${auth}` },
      };

      https.get(url, options, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          // Follow redirect (Twilio often redirects media URLs)
          https.get(res.headers.location, options, (res2) => {
            const chunks = [];
            res2.on('data', (c) => chunks.push(c));
            res2.on('end', () => resolve(Buffer.concat(chunks).toString('base64')));
            res2.on('error', reject);
          }).on('error', reject);
          return;
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks).toString('base64')));
        res.on('error', reject);
      }).on('error', reject);
    });
  }
}

module.exports = { SmsAnalyzer };
