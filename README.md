# ScamStop

**AI-powered protection against phone scams and fraudulent messages.**

ScamStop uses Amazon Nova foundation models to analyze calls and SMS/MMS in real time, detecting scam intent rather than relying on static blocklists. When a threat is detected, it alerts the user, explains why the call or message was flagged, and can automatically terminate dangerous calls.

Live at **[scamstop.app](https://scamstop.app)**

---

## How It Works

Traditional spam filters ask *"Who is calling?"* — ScamStop asks **"What are they actually saying?"**

Instead of matching against known spam numbers, ScamStop analyzes the linguistic and behavioral signals of live conversations: urgency tactics, financial requests, impersonation patterns, and other predatory cues. This means it catches scammers even when they use brand-new, "clean" numbers.

### Call Protection

1. Incoming calls are routed through a Twilio voice gateway
2. **Amazon Nova Sonic** transcribes and analyzes the conversation in real time via bidirectional audio streaming
3. **Amazon Nova Lite** performs periodic contextual analysis on the full transcript
4. A risk score is computed continuously — if it crosses the threshold, the call is terminated with a warning message
5. The operator dashboard shows live transcripts, scores, and keywords as the call progresses

### SMS/MMS Protection

1. The Android app intercepts incoming SMS and MMS messages (as the default messaging app or via notification)
2. Message text and any attached images are sent to the backend for analysis
3. **Amazon Nova Lite** evaluates the content for scam indicators — including OCR-style analysis of images containing phishing text
4. Flagged messages are marked with the scam verdict, score, reason, and keywords directly in the conversation view

---

## Repository Structure

```
scamstop/
├── backend/                    # Node.js server
│   ├── server.js               # Main server — Twilio webhooks, dashboard WebSocket, REST API
│   ├── sonic.js                # Amazon Nova Sonic integration (real-time call analysis)
│   ├── analyzer.js             # Per-utterance scam scoring engine
│   ├── sms-analyzer.js         # SMS/MMS scam analysis (text + images via Nova Lite)
│   ├── call-manager.js         # Call lifecycle management and TwiML generation
│   ├── audio.js                # Audio format conversion utilities
│   ├── config.js               # Configuration loader
│   ├── .env.example            # Template for environment variables
│   ├── public/                 # ScamStop live monitoring dashboard
│   │   ├── index.html          # Real-time call monitor with transcript + analysis panels
│   │   └── icon.svg            # App icon
│   └── test-caller/            # Scam call test harness
│       ├── server.js           # Test call orchestrator — initiates calls with AI scammer personas
│       ├── scammer.js          # AI scammer agent (Nova Lite) with configurable personas
│       ├── scripts.js          # Scam script templates (SSA, IRS, tech support, etc.)
│       └── public/index.html   # Test caller dashboard UI
│
└── android/                    # Android app (Kotlin, Jetpack Compose)
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/scamkill/app/
            ├── MainActivity.kt
            ├── data/
            │   ├── Preferences.kt       # App settings and local scam log
            │   ├── SmsRepository.kt     # SMS/MMS content provider queries
            │   └── ContactsHelper.kt    # Contact lookup for caller ID
            ├── network/
            │   ├── ScamKillApi.kt       # Backend API client
            │   └── ApiModels.kt         # Request/response models
            ├── service/
            │   ├── ScamKillScreeningService.kt  # Call screening service
            │   ├── SmsDeliverReceiver.kt        # SMS receiver (default app mode)
            │   ├── MmsReceiver.kt               # MMS receiver with image extraction
            │   ├── SmsReceiverService.kt        # SMS notification fallback
            │   ├── SmsSendService.kt            # SMS send service
            │   ├── ComposeSmsActivity.kt        # Reply intent handler
            │   └── CallForwardingHelper.kt      # GSM call forwarding via USSD
            ├── viewmodel/
            │   ├── HomeViewModel.kt
            │   ├── MessageLogViewModel.kt
            │   └── SettingsViewModel.kt
            └── ui/
                ├── screens/
                │   ├── MessageLogScreen.kt  # Conversation list + detail with scam flags
                │   └── SettingsScreen.kt    # App configuration
                └── navigation/
                    └── NavGraph.kt
```

---

## Architecture

```
┌──────────────┐     ┌─────────────────────────────────────────────┐
│   Caller     │────▶│  Twilio Voice Gateway                      │
└──────────────┘     │  (routes call, starts media stream)        │
                     └──────────┬──────────────────────────────────┘
                                │ WebSocket (bidirectional audio)
                                ▼
                     ┌─────────────────────────────────────────────┐
                     │  ScamStop Backend (Node.js)                 │
                     │                                             │
                     │  ┌───────────────┐  ┌────────────────────┐  │
                     │  │ Nova Sonic    │  │ Nova Lite          │  │
                     │  │ (real-time    │  │ (contextual        │  │
                     │  │  per-utterance│  │  full-transcript   │  │
                     │  │  analysis)    │  │  analysis, ~5s)    │  │
                     │  └───────┬───────┘  └────────┬───────────┘  │
                     │          │    Risk Score      │              │
                     │          └────────┬───────────┘              │
                     │                   ▼                         │
                     │          ┌─────────────────┐                │
                     │          │ Score > threshold│──▶ TERMINATE  │
                     │          └─────────────────┘    CALL        │
                     └──────────┬──────────────────────────────────┘
                                │ WebSocket
                                ▼
                     ┌─────────────────────┐
                     │  Live Dashboard     │
                     │  (transcript,       │
                     │   scores, keywords) │
                     └─────────────────────┘

┌──────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│ SMS/MMS      │────▶│  Android App        │────▶│  Backend API    │
│ (incoming)   │     │  (intercept,        │     │  /api/sms-check │
└──────────────┘     │   extract images,   │     │  (Nova Lite     │
                     │   display scam flags)│     │   text + image  │
                     └─────────────────────┘     │   analysis)     │
                                                 └─────────────────┘
```

---

## Setup

### Prerequisites

- Node.js 18+
- Android Studio (for the Android app)
- [Twilio account](https://console.twilio.com) with a phone number (voice + SMS enabled)
- [AWS account](https://aws.amazon.com) with access to Amazon Bedrock (Nova Sonic and Nova Lite models)

### Backend

```bash
cd backend
cp .env.example .env
# Edit .env with your Twilio credentials, AWS profile, phone numbers, and server URL

npm install
node server.js
```

The dashboard will be available at `http://localhost:17541/`.

#### Test Caller (optional)

The test-caller harness lets you simulate scam calls with AI-generated scammer personas:

```bash
cd backend/test-caller
npm install
node server.js
```

Dashboard at `http://localhost:17543/`. Configure target numbers via `USER_PHONE_NUMBER` in the backend `.env`.

### Android App

1. Open the `android/` directory in Android Studio
2. Sync Gradle and build
3. Install on device
4. In the app settings, set the backend URL to your server
5. Grant permissions and set ScamStop as the default SMS app and call screener

---

## Key Technologies

| Component | Technology |
|---|---|
| Real-time call analysis | **Amazon Nova Sonic** via AWS Bedrock (bidirectional audio streaming) |
| SMS/MMS & contextual analysis | **Amazon Nova Lite** via AWS Bedrock (text + image understanding) |
| Voice gateway & call routing | **Twilio Programmable Voice** |
| SMS/MMS delivery | **Twilio Programmable Messaging** |
| Backend server | **Node.js** (vanilla HTTP + WebSocket) |
| Android app | **Kotlin** + **Jetpack Compose** |
| Call screening | Android `CallScreeningService` API |
| Default SMS app | Android `SMS_DELIVER` / `WAP_PUSH_DELIVER` receivers |

---

## Test Caller — Scam Simulation

The built-in test-caller harness generates realistic scam calls using Amazon Nova Lite as the "scammer brain." It supports six scam types with configurable severity and urgency:

| Type | Description |
|---|---|
| Gov / SSA | Social Security Administration impersonation |
| IRS / Tax | Tax fraud threats |
| Tech Support | Fake Microsoft/antivirus support |
| Lottery | Prize/sweepstakes scam |
| Bank Fraud | Unauthorized transaction alerts |
| Grandparent | Family emergency impersonation |

Each type has male/female voice options (AWS Polly neural voices) and severity levels from innocent (1) to extreme (5). The test-caller can optionally stream audio to ScamStop for real-time AI monitoring during the call.

---

## License

This project was built for the [Amazon Nova AI Hackathon](https://amazonnovahackathon.devpost.com/).
