/**
 * Nova 2 Lite scam detection analyzer
 *
 * Accumulates call transcript and periodically sends it to
 * Nova Lite for scam pattern analysis. Returns a score (0-100)
 * and verdict.
 */

const {
  BedrockRuntimeClient,
  ConverseCommand,
} = require('@aws-sdk/client-bedrock-runtime');
const { fromIni } = require('@aws-sdk/credential-providers');

const SYSTEM_PROMPT = `You are a fraud detection AI analyzing a live phone call in real-time.
The call is to a recipient who may be vulnerable to scams.

Analyze the conversation transcript for scam indicators:
- Urgency/pressure tactics ("act now", "limited time", "immediately")
- Impersonation of authority (IRS, police, bank, tech support, government)
- Requests for personal information (SSN, credit card, bank account, passwords)
- Gift card or wire transfer requests
- Threats (arrest, legal action, account suspension, deportation)
- Too-good-to-be-true offers (lottery, inheritance, free money)
- Emotional manipulation (fear, greed, sympathy, panic)
- Caller asking victim to stay on the line / not tell anyone
- Remote access requests (install software, share screen)

You MUST respond with ONLY a valid JSON object, no other text:
{
  "score": <0-100 integer, 0=completely safe, 100=definite scam>,
  "verdict": "<SAFE|SUSPICIOUS|LIKELY_SCAM|SCAM>",
  "reason": "<brief 1-sentence explanation>",
  "keywords": ["<detected scam keywords or phrases>"]
}

Scoring guide:
- 0-20: SAFE - Normal conversation, no red flags
- 21-50: SUSPICIOUS - Some unusual elements but inconclusive
- 51-79: LIKELY_SCAM - Multiple scam indicators present
- 80-100: SCAM - Clear fraudulent intent detected`;

class ScamAnalyzer {
  constructor(config) {
    this.config = config;
    this.client = new BedrockRuntimeClient({
      region: config.aws.region,
      credentials: fromIni({ profile: config.aws.profile }),
    });
    this.transcript = [];
    this.lastAnalysisTime = 0;
    this.analysisInterval = 5000; // Minimum 15s between analyses
    this.minWordsForAnalysis = 10; // Need at least this many words
    this.currentResult = {
      score: 0,
      verdict: 'SAFE',
      reason: 'Call just started',
      keywords: [],
    };
    this._analyzing = false;
  }

  /**
   * Add a transcript line
   * @param {string} text - The transcribed text
   * @param {string} speaker - 'caller' or 'user'
   */
  addTranscript(text, speaker = 'caller') {
    if (!text || text.trim().length === 0) return;
    this.transcript.push({
      text: text.trim(),
      speaker,
      time: Date.now(),
    });
  }

  /**
   * Get the full transcript as formatted text
   */
  getFullTranscript() {
    return this.transcript
      .map((t) => `[${t.speaker.toUpperCase()}]: ${t.text}`)
      .join('\n');
  }

  /**
   * Get total word count in transcript
   */
  getWordCount() {
    return this.transcript.reduce(
      (sum, t) => sum + t.text.split(/\s+/).length,
      0
    );
  }

  /**
   * Run scam analysis on the accumulated transcript.
   * Returns null if too soon since last analysis or not enough data.
   * @param {boolean} force - Force analysis regardless of timing
   * @returns {Promise<{score, verdict, reason, keywords}|null>}
   */
  async analyze(force = false) {
    const now = Date.now();

    // Throttle: don't analyze too frequently
    if (!force && now - this.lastAnalysisTime < this.analysisInterval) {
      return null;
    }

    // Need minimum transcript data
    if (!force && this.getWordCount() < this.minWordsForAnalysis) {
      return null;
    }

    // Don't run concurrent analyses
    if (this._analyzing) return null;

    this._analyzing = true;
    this.lastAnalysisTime = now;

    try {
      const command = new ConverseCommand({
        modelId: this.config.models.lite,
        system: [{ text: SYSTEM_PROMPT }],
        messages: [
          {
            role: 'user',
            content: [
              {
                text: `Analyze this ongoing phone call transcript for fraud:\n\n${this.getFullTranscript()}`,
              },
            ],
          },
        ],
        inferenceConfig: {
          maxTokens: 256,
          temperature: 0.2,
        },
      });

      const response = await this.client.send(command);
      const text = response.output?.message?.content?.[0]?.text || '';

      // Parse JSON from the response (handle possible markdown wrapping)
      const jsonMatch = text.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        const result = JSON.parse(jsonMatch[0]);
        this.currentResult = {
          score: Math.max(0, Math.min(100, parseInt(result.score) || 0)),
          verdict: result.verdict || 'UNKNOWN',
          reason: result.reason || '',
          keywords: result.keywords || [],
        };
        return this.currentResult;
      }

      return null;
    } catch (err) {
      console.error('[Analyzer] Error:', err.message);
      return null;
    } finally {
      this._analyzing = false;
    }
  }

  /**
   * Get the latest analysis result without running a new analysis
   */
  getCurrentResult() {
    return this.currentResult;
  }

  /**
   * Reset for a new call
   */
  reset() {
    this.transcript = [];
    this.lastAnalysisTime = 0;
    this.currentResult = {
      score: 0,
      verdict: 'SAFE',
      reason: 'Call just started',
      keywords: [],
    };
    this._analyzing = false;
  }
}

module.exports = { ScamAnalyzer };
