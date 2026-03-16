/**
 * ScamStop Server
 *
 * Orchestrator:
 * 1. Twilio voice webhook -> returns TwiML (Start/Stream + Dial)
 * 2. Twilio Media Stream -> receives forked call audio via WebSocket
 * 3. Nova 2 Sonic -> live transcription of the call
 * 4. Nova 2 Lite -> scam sentiment analysis on accumulated transcript
 * 5. Kill switch -> terminates call via Twilio REST API when scam detected
 * 6. Dashboard -> streams live updates to web UI via WebSocket
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const { WebSocketServer } = require('ws');

const config = require('./config');
const { twilioToSonic } = require('./audio');
const { SonicTranscriber } = require('./sonic');
const { ScamAnalyzer } = require('./analyzer');
const { SmsAnalyzer } = require('./sms-analyzer');
const { CallManager } = require('./call-manager');

const PORT = config.server.port;
const RECORDINGS_DIR = path.join(__dirname, 'recordings');
const callManager = new CallManager(config);
const smsAnalyzer = new SmsAnalyzer(config);

// Active calls: callSid -> { call state }
const activeCalls = new Map();

// SMS messages: messageSid -> { message state }
const messageLog = [];

// Reply-proxy mapping: tracks which sender the recipient should reply to.
// Maps nothing permanently -- we use the most recent non-user sender.
const lastSenderByUser = new Map();

// Dashboard WebSocket clients
const dashboardClients = new Set();

// ─── HTTP Server ─────────────────────────────────────────────────────────────

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }

  // ── Twilio Voice Webhook ───────────────────────────────────────────────
  if (url.pathname === '/voice' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);

    const callSid = params.get('CallSid');
    const from = params.get('From');
    const to = params.get('To');
    const forwardedFrom = params.get('ForwardedFrom');
    const isTestCall = params.get('X_TestCall') === '1';

    console.log(`\n[Call] ${isTestCall ? 'Test ' : ''}Incoming: ${from} -> ${to}`);
    console.log(`[Call] SID: ${callSid}`);
    if (forwardedFrom) console.log(`[Call] Forwarded from: ${forwardedFrom}`);

    // Track the call
    activeCalls.set(callSid, {
      callSid,
      from,
      to,
      forwardedFrom,
      userNumber: config.userPhoneNumber,
      startTime: Date.now(),
      transcript: [],
      scamResult: { score: 0, verdict: 'SAFE', reason: 'Call starting', keywords: [] },
      status: 'ringing',
      terminated: false,
    });

    broadcastDashboard({ type: 'call_start', call: getCallSummary(callSid) });

    // Test calls: stream is started by the test-caller's own TwiML — just acknowledge
    if (isTestCall) {
      console.log(`[Call] Test call registered on dashboard`);
      res.writeHead(200, { 'Content-Type': 'text/xml' });
      return res.end('<Response/>');
    }

    const userNumber = config.userPhoneNumber;
    if (!userNumber) {
      console.error('[Call] USER_PHONE_NUMBER not configured');
      res.writeHead(200, { 'Content-Type': 'text/xml' });
      return res.end(
        '<Response><Say>System error. Please try again later.</Say><Hangup/></Response>'
      );
    }

    // WebSocket URL for the media stream
    const wsBase = config.server.baseUrl.replace(/^http/, 'ws');
    const streamUrl = `${wsBase}/twilio-stream`;

    // Generate TwiML: fork audio + dial user immediately
    const twiml = callManager.generateCallTwiml(streamUrl, userNumber, callSid, from);

    console.log(`[Call] Connecting ${from} -> ${userNumber} with AI monitoring`);

    res.writeHead(200, { 'Content-Type': 'text/xml' });
    return res.end(twiml);
  }

  // ── Twilio Stream Status Callback ──────────────────────────────────────
  if (url.pathname === '/stream/status' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);
    console.log(`[Stream Status] Event=${params.get('StreamEvent')} SID=${params.get('StreamSid')} Error=${params.get('StreamError') || 'none'}`);
    res.writeHead(200);
    return res.end('OK');
  }

  // ── Twilio Dial Status Callback ────────────────────────────────────────
  if (url.pathname === '/voice/status' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);
    const callSid = params.get('CallSid');
    const status = params.get('DialCallStatus') || params.get('CallStatus');

    console.log(`[Call] Status: ${callSid} -> ${status}`);

    const call = activeCalls.get(callSid);
    if (call) {
      call.status = status;
      if (['completed', 'busy', 'no-answer', 'failed', 'canceled'].includes(status)) {
        call.endTime = Date.now();
        broadcastDashboard({ type: 'call_end', call: getCallSummary(callSid) });
      }
    }

    res.writeHead(200, { 'Content-Type': 'text/xml' });
    return res.end('<Response/>');
  }

  // ── Twilio Recording Status Callback ───────────────────────────────────
  if (url.pathname === '/recording/status' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);
    const callSid      = params.get('CallSid');
    const recordingSid = params.get('RecordingSid');
    const recordingUrl = params.get('RecordingUrl');
    const duration     = parseInt(params.get('RecordingDuration') || '0', 10);
    const status       = params.get('RecordingStatus');

    console.log(`[Recording] SID=${recordingSid} | Call=${callSid} | Status=${status} | Duration=${duration}s | URL=${recordingUrl}`);

    if (status === 'completed' && recordingUrl) {
      const filename = `${callSid}.mp3`;
      const filepath = path.join(RECORDINGS_DIR, filename);
      const auth = Buffer.from(`${config.twilio.accountSid}:${config.twilio.authToken}`).toString('base64');

      downloadWithRedirects(`${recordingUrl}.mp3`, auth, 5, (err, stream) => {
        if (err) { console.error('[Recording] Download failed:', err.message); return; }
        const out = fs.createWriteStream(filepath);
        stream.pipe(out);
        out.on('finish', () => {
          const size = fs.statSync(filepath).size;
          console.log(`[Recording] Saved ${filename} (${size} bytes)`);
          const call = activeCalls.get(callSid);
          if (call) call.recordingFile = filename;
          broadcastDashboard({ type: 'recording_ready', callSid, filename, duration });
        });
        out.on('error', e => console.error('[Recording] Write error:', e.message));
      });
    }

    res.writeHead(200); return res.end('OK');
  }

  // ── Serve recording audio files ─────────────────────────────────────────
  if (url.pathname.startsWith('/recordings/') && req.method === 'GET') {
    const filename = path.basename(url.pathname);
    const filepath = path.join(RECORDINGS_DIR, filename);
    if (!filename.endsWith('.mp3') || !fs.existsSync(filepath)) {
      res.writeHead(404); return res.end('Not found');
    }
    const stat = fs.statSync(filepath);
    res.writeHead(200, { 'Content-Type': 'audio/mpeg', 'Content-Length': stat.size });
    return fs.createReadStream(filepath).pipe(res);
  }

  // ── Twilio SMS Webhook ─────────────────────────────────────────────────
  if (url.pathname === '/sms' && req.method === 'POST') {
    const body = await readBody(req);
    const params = new URLSearchParams(body);

    const messageSid = params.get('MessageSid');
    const from = params.get('From');
    const to = params.get('To');
    const messageBody = params.get('Body') || '';
    const numMedia = parseInt(params.get('NumMedia') || '0', 10);

    const media = [];
    for (let i = 0; i < numMedia; i++) {
      media.push({
        url: params.get(`MediaUrl${i}`),
        contentType: params.get(`MediaContentType${i}`),
      });
    }

    const userNumber = config.userPhoneNumber;
    const isReplyFromUser = from === userNumber;

    console.log(`\n[SMS] ${isReplyFromUser ? 'Reply' : 'Incoming'}: ${from} -> ${to}`);
    console.log(`[SMS] SID: ${messageSid} | Body: ${messageBody.slice(0, 80)}${messageBody.length > 80 ? '...' : ''} | Media: ${numMedia}`);

    // If this is a reply from the recipient, forward it to the last sender
    if (isReplyFromUser) {
      const lastSender = lastSenderByUser.get(userNumber);
      if (lastSender) {
        console.log(`[SMS Reply] Forwarding recipient reply to ${lastSender}`);
        await callManager.sendSms(lastSender, messageBody, media.map(m => m.url));

        const entry = {
          messageSid,
          from,
          to: lastSender,
          body: messageBody,
          media,
          timestamp: Date.now(),
          direction: 'reply',
          scamResult: null,
          blocked: false,
        };
        messageLog.unshift(entry);
        broadcastDashboard({ type: 'sms_event', message: entry });
      } else {
        console.log('[SMS Reply] No previous sender to reply to');
      }

      res.writeHead(200, { 'Content-Type': 'text/xml' });
      return res.end('<Response/>');
    }

    // Incoming message from an external sender -- analyze for scams
    const entry = {
      messageSid,
      from,
      to,
      body: messageBody,
      media,
      timestamp: Date.now(),
      direction: 'inbound',
      scamResult: null,
      blocked: false,
      analyzing: true,
    };
    messageLog.unshift(entry);
    broadcastDashboard({ type: 'sms_event', message: entry });

    // Run Nova Lite analysis
    const result = await smsAnalyzer.analyze(messageBody, from, media);
    entry.scamResult = result;
    entry.analyzing = false;

    console.log(`[SMS Analysis] Score: ${result.score}/100 | ${result.verdict} | ${result.reason}`);
    broadcastDashboard({ type: 'sms_analysis', messageSid, result });

    const threshold = config.smsScamThreshold;

    if (result.score >= threshold) {
      // Block: don't forward
      entry.blocked = true;
      console.log(`[SMS] BLOCKED (score ${result.score} >= ${threshold}): ${from}`);
      broadcastDashboard({ type: 'sms_blocked', messageSid, result });

      res.writeHead(200, { 'Content-Type': 'text/xml' });
      return res.end(callManager.generateBlockSmsTwiml());
    }

    // Safe: forward to recipient
    lastSenderByUser.set(userNumber, from);
    console.log(`[SMS] Forwarding to ${userNumber}`);

    const twiml = callManager.generateForwardSmsTwiml(
      from, userNumber, messageBody, media.map(m => m.url)
    );

    res.writeHead(200, { 'Content-Type': 'text/xml' });
    return res.end(twiml);
  }

  // ── Messages API ──────────────────────────────────────────────────────
  if (url.pathname === '/api/messages' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ messages: messageLog.slice(0, 100) }));
  }

  // ── SMS Check API (Android app) ─────────────────────────────────────
  if (url.pathname === '/api/sms-check' && req.method === 'POST') {
    try {
      const body = await readBody(req);
      const { from, body: smsBody, media } = JSON.parse(body);
      if (!from || (!smsBody && (!media || media.length === 0))) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Missing "from" and "body" or "media"' }));
      }
      const result = await smsAnalyzer.analyze(smsBody || '', from, media || []);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(result));
    } catch (err) {
      console.error('[SMS Check] Error:', err.message);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'Analysis failed', score: 0, verdict: 'ERROR' }));
    }
  }

  // ── Kill Switch API ────────────────────────────────────────────────────
  if (
    req.method === 'POST' &&
    url.pathname.startsWith('/api/calls/') &&
    url.pathname.endsWith('/kill')
  ) {
    const callSid = url.pathname.split('/')[3];
    const call = activeCalls.get(callSid);

    if (!call) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'Call not found' }));
    }

    let reason = 'Manual termination by operator';
    try {
      const body = await readBody(req);
      const parsed = JSON.parse(body);
      if (parsed.reason) reason = parsed.reason;
    } catch {}

    const success = await callManager.terminateCall(callSid, reason);
    call.terminated = true;
    call.terminationReason = reason;

    broadcastDashboard({ type: 'call_killed', callSid, reason });

    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ success, callSid, reason }));
  }

  // ── Active Calls API ──────────────────────────────────────────────────
  if (url.pathname === '/api/calls' && req.method === 'GET') {
    const calls = Array.from(activeCalls.keys()).map(getCallSummary).filter(Boolean);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ calls }));
  }

  // ── Health ────────────────────────────────────────────────────────────
  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(
      JSON.stringify({
        ok: true,
        activeCalls: activeCalls.size,
        dashboardClients: dashboardClients.size,
        config: {
          models: config.models,
          scamThreshold: config.scamThreshold,
          twilioConfigured: !!(config.twilio.accountSid && config.twilio.authToken),
        },
      })
    );
  }

  // ── Static Files (Dashboard) ──────────────────────────────────────────
  let filePath = url.pathname === '/' ? '/index.html' : url.pathname;
  const fullPath = path.join(__dirname, 'public', filePath);
  try {
    const stat = fs.statSync(fullPath);
    if (stat.isFile()) {
      const mimeTypes = {
        '.html': 'text/html',
        '.js': 'application/javascript',
        '.css': 'text/css',
        '.png': 'image/png',
        '.svg': 'image/svg+xml',
      };
      const ext = path.extname(fullPath);
      res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'text/plain' });
      return fs.createReadStream(fullPath).pipe(res);
    }
  } catch {}

  res.writeHead(404);
  res.end('Not found');
});

// ─── WebSocket Server ────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname === '/twilio-stream') {
    handleTwilioStream(ws);
  } else if (url.pathname === '/dashboard-ws') {
    handleDashboardWs(ws);
  } else {
    ws.close(1008, 'Unknown path');
  }
});

// ─── Twilio Media Stream Handler ─────────────────────────────────────────────

function handleTwilioStream(ws) {
  let streamSid = null;
  let callSid = null;
  let callerTranscriber = null;   // inbound track = the caller
  let recipientTranscriber = null;  // outbound track = the recipient
  let analyzer = null;            // Nova Lite contextual analyzer
  let analyzerTimer = null;
  let audioChunkCount = 0;
  let trackCounts = { inbound: 0, outbound: 0, unknown: 0 };

  console.log('[Stream] Twilio Media Stream connected');

  ws.on('message', async (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return;
    }

    switch (msg.event) {
      case 'connected':
        console.log('[Stream] Protocol:', msg.protocol);
        break;

      case 'start': {
        streamSid = msg.start.streamSid;
        callSid = msg.start.callSid;
        console.log(`[Stream] Started: stream=${streamSid} call=${callSid}`);

        const call = activeCalls.get(callSid);
        if (call) {
          call.status = 'in-progress';
          call.streamSid = streamSid;
        }

        // Start recording via REST API (more reliable than TwiML record attribute)
        callManager.client.calls(callSid).recordings.create({
          recordingChannels: 'dual',
          recordingStatusCallback: `${config.server.baseUrl}/recording/status`,
          recordingStatusCallbackMethod: 'POST',
          recordingStatusCallbackEvent: ['completed'],
        }).then(rec => {
          console.log(`[Recording] Started: ${rec.sid}`);
        }).catch(err => {
          console.error('[Recording] Failed to start:', err.message);
        });

        callerTranscriber = new SonicTranscriber(config, 'caller');
        recipientTranscriber = new SonicTranscriber(config, 'user');

        // Nova Lite contextual analyzer — analyzes the full conversation
        analyzer = new ScamAnalyzer(config);

        function handleTranscript(evt) {
          if (!evt.text || evt.text.trim().length === 0) return;

          const c = activeCalls.get(callSid);

          // ASSISTANT responses = scam analysis from Nova Sonic (per-utterance)
          if (evt.role === 'ASSISTANT') {
            console.log(`[Sonic AI] ${evt.text}`);

            const scoreMatch = evt.text.match(/SCORE\s+(\d+)/i);
            const verdictMatch = evt.text.match(/VERDICT\s+(SAFE|SUSPICIOUS|LIKELY[_ ]*SCAM|SCAM)/i);
            const reasonMatch = evt.text.match(/REASON\s+(.+?)$/i);
            const jsonMatch = evt.text.match(/\{[\s\S]*\}/);

            let scamResult = null;

            if (scoreMatch) {
              scamResult = {
                score: Math.max(0, Math.min(100, parseInt(scoreMatch[1]) || 0)),
                verdict: verdictMatch ? verdictMatch[1].replace(/\s+/g, '_').toUpperCase() : 'UNKNOWN',
                reason: reasonMatch ? reasonMatch[1].trim() : evt.text,
                keywords: [],
                source: 'sonic',
              };
            } else if (jsonMatch) {
              try {
                const parsed = JSON.parse(jsonMatch[0]);
                scamResult = {
                  score: Math.max(0, Math.min(100, parseInt(parsed.score) || 0)),
                  verdict: parsed.verdict || 'UNKNOWN',
                  reason: parsed.reason || '',
                  keywords: parsed.keywords || [],
                  source: 'sonic',
                };
              } catch {}
            }

            if (scamResult) {
              if (c) c.scamResult = scamResult;
              console.log(`[Sonic]         Score: ${scamResult.score}/100 | ${scamResult.verdict} | ${scamResult.reason}`);
              broadcastDashboard({ type: 'analysis', callSid, result: scamResult });
              checkKillSwitch(callSid, scamResult, 'Sonic');
            }
            return;
          }

          // USER responses = actual call transcript
          const entry = {
            text: evt.text,
            speaker: evt.speaker,
            role: evt.role,
            timestamp: evt.timestamp,
          };

          if (c) c.transcript.push(entry);
          console.log(`[Transcript] [${evt.speaker}] ${evt.text}`);
          broadcastDashboard({ type: 'transcript', callSid, entry });

          // Feed into Nova Lite accumulator
          if (analyzer) {
            analyzer.addTranscript(evt.text, evt.speaker);
          }
        }

        // Wire up both transcribers
        for (const t of [callerTranscriber, recipientTranscriber]) {
          t.on('transcript', handleTranscript);
          t.on('status', (status) => {
            console.log(`[Sonic:${t.speaker}] ${status}`);
            broadcastDashboard({ type: 'sonic_status', callSid, status: `${t.speaker}: ${status}` });
          });
          t.on('error', (err) => {
            console.error(`[Sonic:${t.speaker}] Error:`, err.message);
          });
        }

        // Start caller first; start recipient once caller is confirmed working
        callerTranscriber.start().catch((err) => {
          console.error('[Sonic:caller] Start failed:', err.message);
        });
        setTimeout(() => {
          if (recipientTranscriber) {
            console.log('[Sonic] Starting recipientTranscriber (delayed)...');
            recipientTranscriber.start().catch((err) => {
              console.error('[Sonic:user] Start failed:', err.message);
            });
          }
        }, 3000);

        // Nova Lite timer — run contextual analysis every 15 seconds
        analyzerTimer = setInterval(async () => {
          if (!analyzer) return;
          try {
            const result = await analyzer.analyze();
            if (result) {
              result.source = 'lite';
              const c = activeCalls.get(callSid);
              if (c) c.contextResult = result;
              console.log(`[Nova Lite]     Score: ${result.score}/100 | ${result.verdict} | ${result.reason}`);
              broadcastDashboard({ type: 'context_analysis', callSid, result });
              checkKillSwitch(callSid, result, 'Lite');
            }
          } catch (err) {
            console.error('[Lite] Analysis error:', err.message);
          }
        }, 5000);

        broadcastDashboard({ type: 'stream_start', callSid, streamSid });
        broadcastDashboard({ type: 'call_update', call: getCallSummary(callSid) });
        break;
      }

      case 'media': {
        audioChunkCount++;
        const track = msg.media.track || 'unknown';
        trackCounts[track] = (trackCounts[track] || 0) + 1;

        if (audioChunkCount <= 10 || audioChunkCount % 500 === 0) {
          console.log(`[Stream] chunk #${audioChunkCount} track=${track} (in=${trackCounts.inbound} out=${trackCounts.outbound})`);
        }

        const pcmBase64 = twilioToSonic(msg.media.payload);
        if (track === 'outbound' && recipientTranscriber && recipientTranscriber.isActive) {
          recipientTranscriber.sendAudio(pcmBase64);
        } else if (callerTranscriber && callerTranscriber.isActive) {
          callerTranscriber.sendAudio(pcmBase64);
        }
        break;
      }

      case 'stop':
        console.log(`[Stream] Stopped after ${audioChunkCount} chunks (in=${trackCounts.inbound} out=${trackCounts.outbound})`);
        // Run one final Lite analysis with everything accumulated
        if (analyzer && analyzer.getWordCount() >= 10) {
          analyzer.analyze(true).then((result) => {
            if (result) {
              result.source = 'lite';
              const c = activeCalls.get(callSid);
              if (c) c.contextResult = result;
              console.log(`[Lite Final] Score: ${result.score}/100 | ${result.verdict} | ${result.reason}`);
              broadcastDashboard({ type: 'context_analysis', callSid, result });
            }
          }).catch(() => {});
        }
        cleanup();
        broadcastDashboard({ type: 'stream_stop', callSid, audioChunks: audioChunkCount });
        break;
    }
  });

  ws.on('close', () => {
    console.log('[Stream] WebSocket closed');
    cleanup();
  });

  ws.on('error', (err) => {
    console.error('[Stream] WebSocket error:', err.message);
  });

  function cleanup() {
    if (analyzerTimer) {
      clearInterval(analyzerTimer);
      analyzerTimer = null;
    }
    if (analyzer) {
      analyzer.reset();
      analyzer = null;
    }
    if (callerTranscriber) {
      callerTranscriber.stop().catch(() => {});
      callerTranscriber = null;
    }
    if (recipientTranscriber) {
      recipientTranscriber.stop().catch(() => {});
      recipientTranscriber = null;
    }
  }
}

function checkKillSwitch(callSid, scamResult, source) {
  const c = activeCalls.get(callSid);
  if (!c || c.terminated) return;
  if (scamResult.score >= config.scamThreshold) {
    console.log(`\n  *** KILL SWITCH (${source}) *** Score ${scamResult.score} >= ${config.scamThreshold}\n`);
    c.terminated = true;
    c.terminationReason = `Auto [${source}]: ${scamResult.reason}`;
    callManager.terminateCall(callSid, scamResult.reason);
    broadcastDashboard({ type: 'call_killed', callSid, reason: scamResult.reason, auto: true, source });
  }
}

// ─── Dashboard WebSocket ─────────────────────────────────────────────────────

function handleDashboardWs(ws) {
  dashboardClients.add(ws);
  console.log(`[Dashboard] Client connected (${dashboardClients.size})`);

  // Send current state on connect
  const calls = Array.from(activeCalls.keys()).map(getCallSummary).filter(Boolean);
  ws.send(JSON.stringify({ type: 'init', calls, messages: messageLog.slice(0, 100) }));

  ws.on('close', () => {
    dashboardClients.delete(ws);
  });
  ws.on('error', () => {
    dashboardClients.delete(ws);
  });
}

function broadcastDashboard(msg) {
  const payload = JSON.stringify(msg);
  for (const client of dashboardClients) {
    if (client.readyState === 1) {
      client.send(payload);
    }
  }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getCallSummary(callSid) {
  const call = activeCalls.get(callSid);
  if (!call) return null;
  return {
    callSid: call.callSid,
    from: call.from,
    to: call.to,
    forwardedFrom: call.forwardedFrom,
    userNumber: call.userNumber,
    startTime: call.startTime,
    endTime: call.endTime,
    status: call.status,
    terminated: call.terminated,
    terminationReason: call.terminationReason,
    scamResult: call.scamResult,
    contextResult: call.contextResult,
    transcriptLength: call.transcript.length,
    transcript: call.transcript.slice(-50),
  };
}

function downloadWithRedirects(urlStr, auth, maxRedirects, cb) {
  const parsed = new URL(urlStr);
  const opts = {
    hostname: parsed.hostname,
    path: parsed.pathname + parsed.search,
    headers: { Authorization: `Basic ${auth}` },
  };
  console.log(`[Recording] GET ${urlStr}`);
  https.get(opts, (res) => {
    console.log(`[Recording] HTTP ${res.statusCode}`);
    if ((res.statusCode === 301 || res.statusCode === 302) && res.headers.location && maxRedirects > 0) {
      res.resume();
      return downloadWithRedirects(res.headers.location, auth, maxRedirects - 1, cb);
    }
    if (res.statusCode !== 200) {
      res.resume();
      return cb(new Error(`HTTP ${res.statusCode}`));
    }
    cb(null, res);
  }).on('error', cb);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString()));
    req.on('error', reject);
  });
}

// ─── Start ───────────────────────────────────────────────────────────────────

httpServer.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('  ╔═══════════════════════════════════════════════════╗');
  console.log('  ║            ScamStop Server Running                ║');
  console.log('  ╠═══════════════════════════════════════════════════╣');
  console.log(`  ║  Port:        ${String(PORT).padEnd(37)}║`);
  console.log(`  ║  Dashboard:   http://localhost:${PORT}/`.padEnd(56) + '║');
  console.log(`  ║  Voice:       POST ${config.server.baseUrl}/voice`.padEnd(56) + '║');
  console.log(`  ║  SMS:         POST ${config.server.baseUrl}/sms`.padEnd(56) + '║');
  console.log(`  ║  Sonic Model: ${config.models.sonic}`.padEnd(56) + '║');
  console.log(`  ║  Lite Model:  ${config.models.lite}`.padEnd(56) + '║');
  console.log(`  ║  Threshold:   ${config.scamThreshold}/100`.padEnd(56) + '║');
  console.log(`  ║  Twilio:      ${config.twilio.accountSid ? 'Configured' : '⚠ NOT CONFIGURED'}`.padEnd(56) + '║');
  console.log('  ╚═══════════════════════════════════════════════════╝');
  console.log('');
});
