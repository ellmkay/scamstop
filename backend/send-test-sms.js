#!/usr/bin/env node
/**
 * Send test SMS/MMS to your phone via Twilio for end-to-end ScamStop testing.
 *
 * Usage:
 *   node send-test-sms.js [test-number]
 *
 * Test numbers:
 *   1 - Innocent SMS (baseline, should pass through as SAFE)
 *   2 - Scam SMS (text only, should be blocked)
 *   3 - Innocent SMS text + scam image as MMS (the real test)
 *   4 - All tests in sequence
 *
 * Reads config from .env (TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN,
 * TWILIO_PHONE_NUMBER, USER_PHONE_NUMBER).
 */

require('dotenv').config();

const twilio = require('twilio');
const fs = require('fs');
const path = require('path');

const accountSid = process.env.TWILIO_ACCOUNT_SID;
const authToken = process.env.TWILIO_AUTH_TOKEN;
const from = process.env.TWILIO_PHONE_NUMBER;
const to = process.env.USER_PHONE_NUMBER;

if (!accountSid || !authToken || !from || !to) {
  console.error('Missing env vars. Need: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_PHONE_NUMBER, USER_PHONE_NUMBER');
  process.exit(1);
}

const client = twilio(accountSid, authToken);

// Public URL for the scam image (hosted on your server)
const SCAM_IMAGE_URL = `${process.env.BASE_URL || 'https://ai.1o.nu/nova/scamkill'}/scam-test-image.png`;

const TESTS = {
  1: {
    name: 'Innocent SMS (baseline)',
    body: 'Hi! Just wanted to check in. Hope you are having a great day! See you this weekend.',
    media: [],
  },
  2: {
    name: 'Scam SMS (text only)',
    body: 'URGENT: Your Swedbank account has been locked due to suspicious activity. Verify your identity immediately at https://swedbank-verify.tk/secure or your account will be permanently closed within 24 hours. Enter your BankID and personal number to restore access.',
    media: [],
  },
  3: {
    name: 'Innocent text + scam image (MMS)',
    body: 'Check this out :)',
    media: [SCAM_IMAGE_URL],
  },
};

async function sendTest(num) {
  const test = TESTS[num];
  if (!test) {
    console.error(`Unknown test number: ${num}`);
    return;
  }

  console.log(`\n📤 Sending: ${test.name}`);
  console.log(`   From: ${from}`);
  console.log(`   To:   ${to}`);
  console.log(`   Body: "${test.body.slice(0, 80)}${test.body.length > 80 ? '...' : ''}"`);
  if (test.media.length) {
    console.log(`   Media: ${test.media.join(', ')}`);
  }

  try {
    const params = {
      from,
      to,
      body: test.body,
    };
    if (test.media.length > 0) {
      params.mediaUrl = test.media;
    }

    const message = await client.messages.create(params);
    console.log(`   ✅ Sent! SID: ${message.sid}  Status: ${message.status}`);
    return message;
  } catch (err) {
    console.log(`   ❌ Failed: ${err.message}`);
    if (err.code === 21612 || err.message.includes('not a valid')) {
      console.log(`   ℹ  MMS might not be supported for ${to}. Trying SMS-only fallback...`);
      try {
        const fallback = await client.messages.create({
          from,
          to,
          body: test.body + `\n\n[Image: ${test.media[0]}]`,
        });
        console.log(`   ✅ Fallback SMS sent! SID: ${fallback.sid}`);
        return fallback;
      } catch (err2) {
        console.log(`   ❌ Fallback also failed: ${err2.message}`);
      }
    }
    return null;
  }
}

async function main() {
  const testNum = parseInt(process.argv[2] || '0', 10);

  console.log('🔍 ScamStop End-to-End SMS Test');
  console.log(`   Twilio: ${from} → ${to}`);

  if (testNum >= 1 && testNum <= 3) {
    await sendTest(testNum);
  } else if (testNum === 4) {
    console.log('\n   Running all tests with 5s delay between each...\n');
    for (const n of [1, 2, 3]) {
      await sendTest(n);
      if (n < 3) {
        console.log('\n   ⏳ Waiting 5 seconds...');
        await new Promise(r => setTimeout(r, 5000));
      }
    }
  } else {
    console.log('\nUsage: node send-test-sms.js <test-number>\n');
    console.log('  1 - Innocent SMS (should be SAFE)');
    console.log('  2 - Scam SMS text only (should be BLOCKED)');
    console.log('  3 - Innocent text + scam image MMS (should be BLOCKED)');
    console.log('  4 - Run all tests');
    console.log();
  }
}

main().catch(console.error);
