/**
 * Nova 2 Sonic transcription client
 *
 * Opens a bidirectional stream to Amazon Nova Sonic,
 * feeds in call audio from a single track, and emits transcript events.
 */

const { EventEmitter } = require('events');
const crypto = require('crypto');
const {
  BedrockRuntimeClient,
  InvokeModelWithBidirectionalStreamCommand,
} = require('@aws-sdk/client-bedrock-runtime');
const { NodeHttp2Handler } = require('@smithy/node-http-handler');
const { fromIni } = require('@aws-sdk/credential-providers');

class SonicTranscriber extends EventEmitter {
  /**
   * @param {object} config - App config
   * @param {string} speaker - Speaker label for transcripts (e.g. 'caller', 'user')
   */
  constructor(config, speaker = 'caller') {
    super();
    this.config = config;
    this.speaker = speaker;
    this.isActive = false;
    this.promptName = crypto.randomUUID();
    this.contentName = crypto.randomUUID();
    this.audioContentName = crypto.randomUUID();

    this._queue = [];
    this._queueResolve = null;
    this._closed = false;

    this._audioBuffer = [];
    this._audioBufferSize = 0;
    this._audioChunksSent = 0;
  }

  _pushEvent(eventObj) {
    if (this._closed) return;
    this._queue.push({
      chunk: { bytes: Buffer.from(JSON.stringify(eventObj)) },
    });
    if (this._queueResolve) {
      const resolve = this._queueResolve;
      this._queueResolve = null;
      resolve();
    }
  }

  async *_inputStream() {
    while (!this._closed) {
      while (this._queue.length > 0) {
        yield this._queue.shift();
      }
      if (this._closed) break;
      await new Promise((resolve) => {
        this._queueResolve = resolve;
      });
    }
  }

  async start() {
    this.isActive = true;
    this._closed = false;

    const client = new BedrockRuntimeClient({
      region: this.config.aws.region,
      credentials: fromIni({ profile: this.config.aws.profile }),
      requestHandler: new NodeHttp2Handler({
        requestTimeout: 600000,
        sessionTimeout: 600000,
        disableConcurrentStreams: false,
        maxConcurrentStreams: 20,
      }),
    });

    this._pushEvent({
      event: {
        sessionStart: {
          inferenceConfiguration: {
            maxTokens: 1024,
            topP: 0.9,
            temperature: 0.7,
          },
        },
      },
    });

    this._pushEvent({
      event: {
        promptStart: {
          promptName: this.promptName,
          textOutputConfiguration: { mediaType: 'text/plain' },
          audioOutputConfiguration: {
            mediaType: 'audio/lpcm',
            sampleRateHertz: 24000,
            sampleSizeBits: 16,
            channelCount: 1,
            voiceId: 'matthew',
            encoding: 'base64',
            audioType: 'SPEECH',
          },
        },
      },
    });

    this._pushEvent({
      event: {
        contentStart: {
          promptName: this.promptName,
          contentName: this.contentName,
          type: 'TEXT',
          interactive: true,
          role: 'SYSTEM',
          textInputConfiguration: { mediaType: 'text/plain' },
        },
      },
    });

    const systemPrompt = this.speaker === 'caller'
      ? 'You are a fraud detection assistant monitoring the CALLER side of a phone call to a recipient. ' +
        'You are analyzing a SINGLE utterance in isolation — this is almost never enough to confirm a scam on its own. Default strongly to SAFE. ' +
        'Only score above 50 if the utterance contains an explicit, standalone, unambiguous fraud demand with no possible innocent interpretation: e.g. "buy $500 in Google Play cards right now or you will be arrested", "give me your Social Security number", "send a wire transfer to avoid prosecution". ' +
        'Everything else — greetings, questions, mentioning banks, government agencies, identity, urgency, account issues, offers, or anything that could plausibly come from a legitimate caller — scores 0 to 30. ' +
        'Ambiguous or mildly suspicious phrases score 31-50. Never score above 70 on a single utterance alone. ' +
        'Respond in this exact format: SCORE followed by a number 0 to 100, then VERDICT followed by SAFE or SUSPICIOUS or LIKELY_SCAM or SCAM, then REASON followed by a short explanation. ' +
        'Example: SCORE 5 VERDICT SAFE REASON Normal greeting. ' +
        'Example: SCORE 85 VERDICT SCAM REASON Explicit demand for gift cards to avoid arrest. ' +
        'Keep your response to one short sentence. Always include SCORE, VERDICT, and REASON.'
      : 'You are a transcription assistant. You are listening to a recipient on a phone call. ' +
        'Transcribe what they say. Keep your response very brief - just acknowledge you heard them. ' +
        'Respond with: SCORE 0 VERDICT SAFE REASON Recipient speaking.';

    this._pushEvent({
      event: {
        textInput: {
          promptName: this.promptName,
          contentName: this.contentName,
          content: systemPrompt,
        },
      },
    });

    this._pushEvent({
      event: {
        contentEnd: {
          promptName: this.promptName,
          contentName: this.contentName,
        },
      },
    });

    this._pushEvent({
      event: {
        contentStart: {
          promptName: this.promptName,
          contentName: this.audioContentName,
          type: 'AUDIO',
          interactive: true,
          role: 'USER',
          audioInputConfiguration: {
            mediaType: 'audio/lpcm',
            sampleRateHertz: 16000,
            sampleSizeBits: 16,
            channelCount: 1,
            audioType: 'SPEECH',
            encoding: 'base64',
          },
        },
      },
    });

    const command = new InvokeModelWithBidirectionalStreamCommand({
      modelId: this.config.models.sonic,
      body: this._inputStream(),
    });

    try {
      this.emit('status', 'connecting');
      const response = await client.send(command);
      this.emit('status', 'connected');

      let eventCount = 0;
      for await (const event of response.body) {
        eventCount++;
        if (eventCount <= 3) {
          console.log(`[Sonic:${this.speaker}] Raw event #${eventCount}: keys=${Object.keys(event).join(',')}`);
        }
        if (!this.isActive) break;

        if (event.chunk?.bytes) {
          let data;
          try {
            data = JSON.parse(new TextDecoder().decode(event.chunk.bytes));
          } catch {
            continue;
          }
          try {
            this._handleResponseEvent(data);
          } catch (err) {
            console.error(`[Sonic:${this.speaker}] Error handling event:`, err.message);
          }
        }

        if (event.internalServerException) {
          this.emit('error', new Error(event.internalServerException.message));
        }
        if (event.modelStreamErrorException) {
          this.emit('error', new Error(event.modelStreamErrorException.message));
        }
      }

      this.emit('status', 'disconnected');
    } catch (err) {
      this.emit('error', err);
      this.emit('status', 'error');
    }
  }

  _handleResponseEvent(data) {
    if (!data.event) return;

    const eventType = Object.keys(data.event)[0];

    if (data.event.contentStart) {
      this._currentRole = data.event.contentStart.role;
    }

    if (data.event.textOutput) {
      const text = data.event.textOutput.content;
      const role = this._currentRole || 'USER';

      console.log(`[Sonic:${this.speaker}] [${role}] ${text}`);

      this.emit('transcript', {
        text,
        role,
        speaker: role === 'ASSISTANT' ? 'ai' : this.speaker,
        timestamp: Date.now(),
      });
    }
  }

  /**
   * Send a PCM audio chunk. Buffers into ~500ms blocks.
   * @param {string} pcmBase64 - Base64-encoded PCM 16kHz audio
   */
  sendAudio(pcmBase64) {
    if (!this.isActive || this._closed) return;

    const pcmBuf = Buffer.from(pcmBase64, 'base64');
    this._audioBuffer.push(pcmBuf);
    this._audioBufferSize += pcmBuf.length;

    if (this._audioBufferSize >= 16000) {
      const combined = Buffer.concat(this._audioBuffer);
      this._audioBuffer = [];
      this._audioBufferSize = 0;
      this._audioChunksSent++;

      if (this._audioChunksSent <= 3) {
        console.log(`[Sonic:${this.speaker}] Sending chunk #${this._audioChunksSent}: ${combined.length} bytes`);
      }

      this._pushEvent({
        event: {
          audioInput: {
            promptName: this.promptName,
            contentName: this.audioContentName,
            content: combined.toString('base64'),
          },
        },
      });
    }
  }

  async stop() {
    if (!this.isActive) return;
    this.isActive = false;

    try {
      // Flush remaining audio
      if (this._audioBuffer.length > 0) {
        const combined = Buffer.concat(this._audioBuffer);
        this._audioBuffer = [];
        this._audioBufferSize = 0;
        this._pushEvent({
          event: {
            audioInput: {
              promptName: this.promptName,
              contentName: this.audioContentName,
              content: combined.toString('base64'),
            },
          },
        });
      }

      await new Promise((r) => setTimeout(r, 100));

      this._pushEvent({
        event: { contentEnd: { promptName: this.promptName, contentName: this.audioContentName } },
      });
      await new Promise((r) => setTimeout(r, 100));

      this._pushEvent({
        event: { promptEnd: { promptName: this.promptName } },
      });
      await new Promise((r) => setTimeout(r, 100));

      this._pushEvent({ event: { sessionEnd: {} } });
      await new Promise((r) => setTimeout(r, 200));
    } catch (err) {
      console.error(`[Sonic:${this.speaker}] Error during stop:`, err.message);
    }

    this._closed = true;
    if (this._queueResolve) this._queueResolve();
  }
}

module.exports = { SonicTranscriber };
