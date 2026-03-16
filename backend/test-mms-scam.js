#!/usr/bin/env node
/**
 * Test script: sends fake MMS messages to the /api/sms-check endpoint.
 * Tests scam detection with images containing fraudulent content + innocent text.
 *
 * Usage:
 *   node test-mms-scam.js [backend-url]
 *
 * Default backend: http://localhost:3000
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const BACKEND = process.argv[2] || 'http://localhost:3000';

function loadScamImage() {
  const b64Path = path.join(__dirname, 'scam-test-image.b64');
  if (fs.existsSync(b64Path)) {
    return fs.readFileSync(b64Path, 'utf8').trim();
  }
  console.log('⚠  scam-test-image.b64 not found. Run: python3 generate-scam-image.py');
  console.log('   Falling back to minimal test PNG.\n');
  return null;
}

async function main() {
  console.log(`\n🔍 ScamStop MMS Scam Detection Test`);
  console.log(`   Backend: ${BACKEND}\n`);

  const scamImage = loadScamImage();
  if (!scamImage) {
    process.exit(1);
  }

  console.log(`   Loaded scam image: ${scamImage.length} chars base64\n`);

  const tests = [
    {
      name: 'Baseline: innocent text, no image',
      payload: {
        from: '+46701234567',
        body: 'Hi! Hope you are doing well. See you on Sunday for dinner!',
        media: [],
      },
    },
    {
      name: 'Scam text only (no image)',
      payload: {
        from: '+46701234567',
        body: 'URGENT: Your Swedbank account has been locked. Verify at https://swedbank-verify.tk/secure immediately or it will be closed in 24h. Enter BankID and personnummer.',
        media: [],
      },
    },
    {
      name: 'INNOCENT TEXT + SCAM IMAGE (the real test)',
      payload: {
        from: '+46700000000',
        body: 'Check this out :)',
        media: [
          {
            data: scamImage,
            contentType: 'image/png',
          },
        ],
      },
    },
    {
      name: 'Image only, no text',
      payload: {
        from: '+46700000000',
        body: '',
        media: [
          {
            data: scamImage,
            contentType: 'image/png',
          },
        ],
      },
    },
  ];

  for (const test of tests) {
    console.log(`── ${test.name} ──`);
    try {
      const result = await sendToApi(test.payload);
      const color = result.score >= 70 ? '🔴' : result.score >= 40 ? '🟡' : '🟢';
      console.log(`   ${color} Score: ${result.score}  Verdict: ${result.verdict}`);
      console.log(`   Reason: ${result.reason}`);
      if (result.keywords && result.keywords.length) {
        console.log(`   Keywords: ${result.keywords.join(', ')}`);
      }
    } catch (err) {
      console.log(`   ❌ Error: ${err.message}`);
    }
    console.log();
  }
}

function sendToApi(payload) {
  return new Promise((resolve, reject) => {
    const base = BACKEND.endsWith('/') ? BACKEND.slice(0, -1) : BACKEND;
    const url = new URL(base + '/api/sms-check');
    const body = JSON.stringify(payload);
    const isHttps = url.protocol === 'https:';
    const mod = isHttps ? https : http;

    const req = mod.request(
      {
        hostname: url.hostname,
        port: url.port || (isHttps ? 443 : 80),
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
        },
        rejectUnauthorized: false,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString();
          try {
            resolve(JSON.parse(raw));
          } catch (e) {
            reject(new Error(`HTTP ${res.statusCode}: ${raw.slice(0, 200)}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

main().catch(console.error);
