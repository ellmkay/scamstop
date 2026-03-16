/**
 * Test Caller — turn-based scam-call test harness for ScamStop.
 */

const http  = require('http');
const https = require('https');
const fs    = require('fs');
const path  = require('path');

require('dotenv').config({ path: path.join(__dirname, '../.env') });

const twilio             = require('twilio');
const { WebSocketServer } = require('ws');
const { Scammer }        = require('./scammer');

const PORT          = parseInt(process.env.TEST_CALLER_PORT || '17543', 10);
const BASE_URL      = process.env.TEST_CALLER_BASE_URL || `http://localhost:${PORT}`;
const SCAMSTOP_URL  = process.env.BASE_URL || 'https://scamstop.app/nova/scamkill';

const TWILIO_ACCOUNT_SID = process.env.TEST_CALLER_ACCOUNT_SID || process.env.TWILIO_ACCOUNT_SID;
const TWILIO_AUTH_TOKEN  = process.env.TEST_CALLER_AUTH_TOKEN  || process.env.TWILIO_AUTH_TOKEN;
const TWILIO_NUMBER      = process.env.TEST_CALLER_FROM_NUMBER || process.env.TWILIO_PHONE_NUMBER;
const TARGET_NUMBER      = process.env.USER_PHONE_NUMBER || '';
const AWS_PROFILE        = process.env.AWS_PROFILE || 'default';
const AWS_REGION         = process.env.AWS_REGION  || 'us-east-1';
const MODEL_ID           = process.env.NOVA_LITE_MODEL || 'us.amazon.nova-2-lite-v1:0';

const client = twilio(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN);

const RECORDINGS_DIR = path.join(__dirname, 'recordings');
if (!fs.existsSync(RECORDINGS_DIR)) fs.mkdirSync(RECORDINGS_DIR);

// Active sessions: callSid → { scammer, to, scamType, severity, urgency, status, voice, maxTurns, log }
const sessions = new Map();

// Dashboard WebSocket clients
const dashboardClients = new Set();

function broadcast(msg) {
  const payload = JSON.stringify(msg);
  for (const ws of dashboardClients) {
    if (ws.readyState === 1) ws.send(payload);
  }
}

function sessionSummary(callSid) {
  const s = sessions.get(callSid);
  if (!s) return null;
  return {
    callSid,
    to:        s.to,
    scamType:  s.scamType,
    severity:  s.severity,
    urgency:   s.urgency,
    status:    s.status,
    startTime: s.startTime,
    log:       s.log,
  };
}

// Polly Neural voices per scam type (Neural = far more natural than standard)
const VOICES = {
  ssa:         { male: 'Polly.Matthew-Neural', female: 'Polly.Joanna-Neural'  },
  irs:         { male: 'Polly.Matthew-Neural', female: 'Polly.Salli-Neural'   },
  tech:        { male: 'Polly.Brian-Neural',   female: 'Polly.Amy-Neural'     }, // UK accents
  lottery:     { male: 'Polly.Joey-Neural',    female: 'Polly.Kendra-Neural'  },
  bank:        { male: 'Polly.Stephen-Neural', female: 'Polly.Joanna-Neural'  }, // Stephen = authoritative
  grandparent: { male: 'Polly.Kevin-Neural',   female: 'Polly.Ivy-Neural'     }, // Kevin = younger male
};

function getVoice(scamType, voicePref) {
  return (VOICES[scamType] || VOICES.ssa)[voicePref] || 'Polly.Matthew';
}

function escapeSsml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function turnTwiml({ text, callSid, voice, maxTurns, currentTurn, scamstop }) {
  const safe       = escapeSsml(text);
  const actionUrl  = `${BASE_URL}/api/turn/${encodeURIComponent(callSid)}`;
  const streamUrl  = SCAMSTOP_URL.replace(/^http/, 'ws') + '/twilio-stream';
  const streamStatusUrl = `${SCAMSTOP_URL}/stream/status`;

  // First turn + scamstop mode: start the ScamStop monitoring stream
  const streamBlock = (scamstop && currentTurn === 1) ? `
  <Start>
    <Stream url="${streamUrl}" track="both_tracks" name="scamstop-monitor"
      statusCallback="${streamStatusUrl}" statusCallbackMethod="POST"/>
  </Start>` : '';

  if (currentTurn >= maxTurns) {
    return `<?xml version="1.0" encoding="UTF-8"?>
<Response>${streamBlock}
  <Say voice="${voice}">${safe}</Say>
  <Pause length="2"/>
  <Hangup/>
</Response>`;
  }

  return `<?xml version="1.0" encoding="UTF-8"?>
<Response>${streamBlock}
  <Say voice="${voice}">${safe}</Say>
  <Gather input="speech" action="${actionUrl}" method="POST"
    timeout="4" speechTimeout="1" language="en-US"
    enhanced="true" actionOnEmptyResult="true">
  </Gather>
</Response>`;
}

// ─── HTTP Server ──────────────────────────────────────────────────────────────

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

  // ── Dashboard config (target numbers from env, no secrets) ─────────────
  if (url.pathname === '/api/config' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ targetNumber: TARGET_NUMBER }));
  }

  // ── Initial TwiML (Twilio GETs this when the target picks up) ──────────
  if (url.pathname === '/twiml' && req.method === 'GET') {
    const p        = url.searchParams;
    const callSid  = p.get('CallSid') || p.get('callSid') || 'unknown';
    const scamType = p.get('scamType')  || 'ssa';
    const severity = parseInt(p.get('severity')  || '3', 10);
    const urgency  = parseInt(p.get('urgency')   || '3', 10);
    const maxTurns = parseInt(p.get('maxTurns')  || '8', 10);
    const voice    = p.get('voice') || 'Polly.Matthew';
    const scamstop = p.get('scamstop') === '1';

    // Create session
    const gender  = p.get('gender') === 'female' ? 'female' : 'male';

    const scammer = new Scammer({
      scamType, severity, urgency, gender,
      awsProfile: AWS_PROFILE,
      awsRegion:  AWS_REGION,
      modelId:    MODEL_ID,
    });

    sessions.set(callSid, {
      scammer,
      to:        p.get('to') || '',
      scamType, severity, urgency, voice, maxTurns, scamstop,
      status:    'in-progress',
      startTime: Date.now(),
      log:       [],
    });

    const opener = scammer.opener();
    const session = sessions.get(callSid);
    session.log.push({ role: 'scammer', text: opener, ts: Date.now() });

    console.log(`[Session] ${callSid} | ${scamType} sev=${severity} urg=${urgency}`);
    console.log(`[Scammer] ${opener}`);

    broadcast({ type: 'call_answered', callSid, scamType, severity, urgency });
    broadcast({ type: 'turn', callSid, role: 'scammer', text: opener });

    res.writeHead(200, { 'Content-Type': 'text/xml' });
    return res.end(turnTwiml({ text: opener, callSid, voice, maxTurns, currentTurn: 1, scamstop }));
  }

  // ── Turn handler ────────────────────────────────────────────────────────
  if (url.pathname.startsWith('/api/turn/') && req.method === 'POST') {
    const callSid = decodeURIComponent(url.pathname.split('/api/turn/')[1]);
    const body    = await readBody(req);
    const params  = new URLSearchParams(body);

    const speechResult = params.get('SpeechResult') || '';
    const noSpeech     = url.searchParams.get('noSpeech') === '1' || !speechResult.trim();

    const session = sessions.get(callSid);
    if (!session) {
      res.writeHead(200, { 'Content-Type': 'text/xml' });
      return res.end(`<?xml version="1.0" encoding="UTF-8"?><Response><Hangup/></Response>`);
    }

    const { scammer, voice, maxTurns, scamstop } = session;
    const currentTurn = scammer.turnCount() + 1;

    const targetText = noSpeech ? '[silence]' : speechResult.trim();

    // Log target's utterance
    session.log.push({ role: 'target', text: targetText, ts: Date.now() });
    console.log(`[Target  ] (turn ${currentTurn}) ${targetText}`);
    broadcast({ type: 'turn', callSid, role: 'target', text: targetText });

    // Generate scammer reply
    const reply = await scammer.respond(targetText);
    session.log.push({ role: 'scammer', text: reply, ts: Date.now() });
    console.log(`[Scammer ] ${reply}`);
    broadcast({ type: 'turn', callSid, role: 'scammer', text: reply });

    if (currentTurn >= maxTurns) sessions.delete(callSid);

    res.writeHead(200, { 'Content-Type': 'text/xml' });
    return res.end(turnTwiml({ text: reply, callSid, voice, maxTurns, currentTurn, scamstop }));
  }

  // ── Twilio status callback ──────────────────────────────────────────────
  if (url.pathname === '/api/status' && req.method === 'POST') {
    const body   = await readBody(req);
    const params = new URLSearchParams(body);
    const callSid = params.get('CallSid');
    const status  = params.get('CallStatus');

    const session = sessions.get(callSid);
    if (session) {
      session.status = status;
      if (['completed', 'busy', 'no-answer', 'failed', 'canceled'].includes(status)) {
        session.endTime = Date.now();
        sessions.delete(callSid);
      }
    }

    console.log(`[Status  ] ${callSid} → ${status}`);
    broadcast({ type: 'call_status', callSid, status });

    res.writeHead(200); return res.end('OK');
  }

  // ── Hang up a call ─────────────────────────────────────────────────────
  if (url.pathname.startsWith('/api/hangup/') && req.method === 'POST') {
    const callSid = decodeURIComponent(url.pathname.split('/api/hangup/')[1]);
    try {
      await client.calls(callSid).update({ status: 'completed' });
      sessions.delete(callSid);
      broadcast({ type: 'call_status', callSid, status: 'completed' });
      console.log(`[Hangup  ] ${callSid}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true }));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: err.message }));
    }
  }

  // ── Active calls (for page reload sync) ────────────────────────────────
  if (url.pathname === '/api/calls' && req.method === 'GET') {
    const calls = Array.from(sessions.keys()).map(sessionSummary).filter(Boolean);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ calls }));
  }

  // ── Initiate call ──────────────────────────────────────────────────────
  if (url.pathname === '/api/call' && req.method === 'POST') {
    let body;
    try { body = JSON.parse(await readBody(req)); }
    catch { res.writeHead(400); return res.end(JSON.stringify({ error: 'Invalid JSON' })); }

    const {
      to,
      from,
      scamType  = 'ssa',
      severity  = 3,
      urgency   = 3,
      maxTurns  = 8,
      voicePref = 'male',
      scamstop  = false,
    } = body;

    const fromNumber = (from && from.trim()) || TWILIO_NUMBER;
    if (!to) { res.writeHead(400); return res.end(JSON.stringify({ error: 'Missing "to"' })); }
    if (!fromNumber) { res.writeHead(500); return res.end(JSON.stringify({ error: 'No "from" number set' })); }

    const voice    = getVoice(scamType, voicePref);
    const twimlParams = new URLSearchParams({ scamType, severity, urgency, maxTurns, voice, gender: voicePref, to, scamstop: scamstop ? '1' : '0' });
    const twimlUrl = `${BASE_URL}/twiml?${twimlParams}`;
    const statusUrl = `${BASE_URL}/api/status`;

    try {
      const call = await client.calls.create({
        to, from: fromNumber,
        url: twimlUrl, method: 'GET',
        statusCallback: statusUrl, statusCallbackMethod: 'POST',
        statusCallbackEvent: ['initiated', 'ringing', 'answered', 'completed'],
        record: true,
        recordingStatusCallback: `${BASE_URL}/api/recording/status`,
        recordingStatusCallbackMethod: 'POST',
      });

      console.log(`[Call    ] ${call.sid} → ${to} | ${scamType} sev=${severity} urg=${urgency} turns=${maxTurns}`);

      // Pre-register with 'ringing' status so dashboard shows it immediately
      sessions.set(call.sid, {
        scammer:   null, // will be set when target picks up
        to, scamType, severity, urgency, voice, maxTurns,
        status:    'ringing',
        startTime: Date.now(),
        log:       [],
      });

      broadcast({ type: 'call_started', callSid: call.sid, to, scamType, severity, urgency, status: 'ringing' });
      if (scamstop) notifyScamStop(call.sid, fromNumber, to);

      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true, callSid: call.sid, to }));
    } catch (err) {
      console.error('[Call    ] Twilio error:', err.message);
      res.writeHead(500); return res.end(JSON.stringify({ error: err.message }));
    }
  }

  // ── Recording status callback ───────────────────────────────────────────
  if (url.pathname === '/api/recording/status' && req.method === 'POST') {
    const body   = await readBody(req);
    const params = new URLSearchParams(body);
    const callSid      = params.get('CallSid');
    const recordingUrl = params.get('RecordingUrl');
    const status       = params.get('RecordingStatus');
    const duration     = parseInt(params.get('RecordingDuration') || '0', 10);

    console.log(`[Recording] CallSid=${callSid} Status=${status} Duration=${duration}s`);

    if (status === 'completed' && recordingUrl) {
      const filename = `${callSid}.mp3`;
      const filepath = path.join(RECORDINGS_DIR, filename);
      const auth = Buffer.from(`${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}`).toString('base64');

      downloadWithRedirects(`${recordingUrl}.mp3`, auth, 5, (err, stream) => {
        if (err) { console.error('[Recording] Download failed:', err.message); return; }
        const out = fs.createWriteStream(filepath);
        stream.pipe(out);
        out.on('finish', () => {
          console.log(`[Recording] Saved ${filename} (${fs.statSync(filepath).size} bytes)`);
          const session = sessions.get(callSid);
          if (session) session.recordingFile = filename;
          broadcast({ type: 'recording_ready', callSid, filename, duration });
        });
        out.on('error', e => console.error('[Recording] Write error:', e.message));
      });
    }

    res.writeHead(200); return res.end('OK');
  }

  // ── Serve recordings ────────────────────────────────────────────────────
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

  // ── Static files ───────────────────────────────────────────────────────
  let filePath = url.pathname === '/' ? '/index.html' : url.pathname;
  const fullPath = path.join(__dirname, 'public', filePath);
  try {
    const stat = fs.statSync(fullPath);
    if (stat.isFile()) {
      const ext = path.extname(fullPath);
      const mime = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };
      res.writeHead(200, { 'Content-Type': mime[ext] || 'text/plain' });
      return fs.createReadStream(fullPath).pipe(res);
    }
  } catch {}

  res.writeHead(404); res.end('Not found');
});

// ─── WebSocket Server ─────────────────────────────────────────────────────────

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname !== '/ws') { ws.close(); return; }

  dashboardClients.add(ws);
  console.log(`[WS      ] Dashboard connected (${dashboardClients.size})`);

  // Send current state
  const calls = Array.from(sessions.keys()).map(sessionSummary).filter(Boolean);
  ws.send(JSON.stringify({ type: 'init', calls }));

  ws.on('close', () => dashboardClients.delete(ws));
  ws.on('error', () => dashboardClients.delete(ws));
});

// ─── Helpers ──────────────────────────────────────────────────────────────────

// Notify the ScamStop server about a test call so it appears on the dashboard
function notifyScamStop(callSid, from, to) {
  const body = new URLSearchParams({ CallSid: callSid, From: from, To: to, X_TestCall: '1' }).toString();
  const url  = new URL(`${SCAMSTOP_URL}/voice`);
  const opts = {
    hostname: url.hostname,
    port:     url.port || (url.protocol === 'https:' ? 443 : 80),
    path:     url.pathname,
    method:   'POST',
    headers:  { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body) },
  };
  const mod = url.protocol === 'https:' ? require('https') : require('http');
  const req = mod.request(opts);
  req.on('error', () => {});
  req.write(body);
  req.end();
}

function downloadWithRedirects(urlStr, auth, maxRedirects, cb) {
  const parsed = new URL(urlStr);
  const opts = { hostname: parsed.hostname, path: parsed.pathname + parsed.search, headers: { Authorization: `Basic ${auth}` } };
  console.log(`[Recording] GET ${urlStr}`);
  https.get(opts, (res) => {
    console.log(`[Recording] HTTP ${res.statusCode}`);
    if ((res.statusCode === 301 || res.statusCode === 302) && res.headers.location && maxRedirects > 0) {
      res.resume();
      return downloadWithRedirects(res.headers.location, auth, maxRedirects - 1, cb);
    }
    if (res.statusCode !== 200) { res.resume(); return cb(new Error(`HTTP ${res.statusCode}`)); }
    cb(null, res);
  }).on('error', cb);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString()));
    req.on('error', reject);
  });
}

httpServer.listen(PORT, '0.0.0.0', () => {
  console.log(`\n  ╔══════════════════════════════════════╗`);
  console.log(`  ║     Test Caller Running              ║`);
  console.log(`  ╠══════════════════════════════════════╣`);
  console.log(`  ║  Port:  ${String(PORT).padEnd(29)}║`);
  console.log(`  ║  UI:    http://localhost:${PORT}/`.padEnd(43) + '║');
  console.log(`  ║  From:  ${String(TWILIO_NUMBER || '⚠ NOT SET').padEnd(29)}║`);
  console.log(`  ║  Model: ${String(MODEL_ID).padEnd(29)}║`);
  console.log(`  ╚══════════════════════════════════════╝\n`);
});
