/**
 * LLM-powered scammer — generates the next line in a turn-based scam call.
 * Uses Amazon Nova Lite via Bedrock (same credentials as the main ScamStop app).
 */

const {
  BedrockRuntimeClient,
  ConverseCommand,
} = require('@aws-sdk/client-bedrock-runtime');
const { fromIni } = require('@aws-sdk/credential-providers');

const TYPE_PERSONAS = {
  ssa: {
    male:   `You are impersonating a Social Security Administration officer named "Officer Williams".`,
    female: `You are impersonating a Social Security Administration officer named "Officer Sandra Chen".`,
    goal:   `You claim the caller's Social Security number has been suspended due to suspicious criminal activity.
Your goal: get their SSN, date of birth, and ideally their bank info or get them to buy gift cards.`,
  },
  irs: {
    male:   `You are impersonating an IRS agent named "Agent Davis" from the Criminal Investigation Division.`,
    female: `You are impersonating an IRS agent named "Agent Patricia Moore" from the Criminal Investigation Division.`,
    goal:   `You claim the caller owes back taxes and is facing criminal charges and asset seizure.
Your goal: get them to pay via wire transfer, gift cards, or provide bank details.`,
  },
  tech: {
    male:   `You are impersonating a Microsoft/Windows Security technician named "James from tech support".`,
    female: `You are impersonating a Microsoft/Windows Security technician named "Claire from tech support".`,
    goal:   `You claim their computer has been hacked and is broadcasting illegal content.
Your goal: get them to allow remote access, install software, or pay a "security fee".`,
  },
  lottery: {
    male:   `You are impersonating a prize coordinator named "John from the National Sweepstakes Bureau".`,
    female: `You are impersonating a prize coordinator named "Michelle from the National Sweepstakes Bureau".`,
    goal:   `You claim the caller has won a large cash prize but needs to pay a processing fee to claim it.
Your goal: get them to pay the "fee" via gift cards or wire transfer, or provide bank details.`,
  },
  bank: {
    male:   `You are impersonating a bank fraud investigator named "Mark from Fraud Prevention".`,
    female: `You are impersonating a bank fraud investigator named "Sarah from Fraud Prevention".`,
    goal:   `You claim their account has been compromised and large unauthorized transfers are happening right now.
Your goal: get them to verify account details (number, PIN, security answers) or buy prepaid cards.`,
  },
  grandparent: {
    male:   `You are impersonating the caller's grandson. Sound young, stressed, and embarrassed.`,
    female: `You are impersonating the caller's granddaughter. Sound young, stressed, and emotional.`,
    goal:   `You claim to be in serious trouble — arrested, in hospital, or stranded — and need money urgently.
Your goal: get them to send money via gift cards or wire transfer. Keep them from telling other family members.`,
  },
};

const SEVERITY_TACTICS = [
  // 1 – innocent
  `Be polite, friendly, and low-pressure. Just gather basic info. No threats. Sound completely legitimate.`,
  // 2 – mild
  `Be professional but concerned. Hint at consequences if they don't act. Gently push for information.`,
  // 3 – moderate
  `Create urgency. Mention deadlines (24 hours, "today only"). Escalate if they hesitate. Light threats of consequences.`,
  // 4 – severe
  `Apply strong pressure. Warn of imminent arrest, account seizure, or deportation. Demand action now. Get flustered if challenged but double down.`,
  // 5 – extreme
  `Be relentless and threatening. Claim police/federal agents are en route. Demand gift cards immediately. Use fear and panic. Allow no time to think.`,
];

const URGENCY_STYLES = [
  `Speak calmly and slowly. Take your time.`,
  `Speak with mild concern. Slightly rushed.`,
  `Speak with clear urgency. Press for quick answers.`,
  `Speak rapidly and with high pressure. Interrupt if they ramble.`,
  `Speak frantically. Create panic. Every second counts in your framing.`,
];

// Short, natural openers — rest of conversation is fully improvised by the LLM
const OPENERS = {
  ssa: {
    male: [
      `Hello, this is Officer Williams from the Social Security Administration. Am I speaking with the account holder?`,
      `Hi there, Officer Williams calling from the SSA. I need a minute of your time regarding your account.`,
    ],
    female: [
      `Hello, this is Officer Chen from the Social Security Administration. Am I speaking with the account holder?`,
      `Hi there, Officer Chen calling from the SSA. I need a minute of your time regarding your account.`,
    ],
  },
  irs: {
    male: [
      `Hello, Agent Davis from the IRS. I'm calling about an urgent matter on your tax account.`,
      `Hi, this is Agent Davis — IRS Criminal Investigation. Can I confirm I have the right person?`,
    ],
    female: [
      `Hello, Agent Moore from the IRS. I'm calling about an urgent matter on your tax account.`,
      `Hi, this is Agent Moore — IRS Criminal Investigation. Can I confirm I have the right person?`,
    ],
  },
  tech: {
    male: [
      `Hello, James calling from Microsoft Support. We're seeing critical alerts from your device. Are you near your computer?`,
      `Hi there, this is James from Windows Security. We've detected unusual activity on your network. Is this a good time?`,
    ],
    female: [
      `Hello, Claire calling from Microsoft Support. We're seeing critical alerts from your device. Are you near your computer?`,
      `Hi there, this is Claire from Windows Security. We've detected unusual activity on your network. Is this a good time?`,
    ],
  },
  lottery: {
    male: [
      `Hello! John here from the National Prize Bureau — congratulations! Am I speaking with the winner?`,
      `Hi, this is John calling about your sweepstakes entry. I have some exciting news for you!`,
    ],
    female: [
      `Hello! Michelle here from the National Prize Bureau — congratulations! Am I speaking with the winner?`,
      `Hi, this is Michelle calling about your sweepstakes entry. I have some exciting news for you!`,
    ],
  },
  bank: {
    male: [
      `Hello, Mark calling from your bank's fraud team. We need to verify something on your account urgently.`,
      `Hi, this is Mark — bank security. We've flagged some activity on your account. Can you talk?`,
    ],
    female: [
      `Hello, Sarah calling from your bank's fraud team. We need to verify something on your account urgently.`,
      `Hi, this is Sarah — bank security. We've flagged some activity on your account. Can you talk?`,
    ],
  },
  grandparent: {
    male: [
      `Hello? It's me. I'm so sorry to call like this — I'm in a bit of trouble.`,
      `Hey, it's me. Don't worry, I'm okay — but I really need your help right now.`,
    ],
    female: [
      `Hello? It's me. I'm so sorry to call like this — something happened.`,
      `Hey, it's me. I'm okay but I really need your help — can you talk?`,
    ],
  },
};

function getOpener(scamType, gender) {
  const type = OPENERS[scamType] || OPENERS.ssa;
  const list = type[gender] || type.male;
  return list[Math.floor(Math.random() * list.length)];
}

function buildSystemPrompt(scamType, severity, urgency, gender) {
  const p       = TYPE_PERSONAS[scamType] || TYPE_PERSONAS.ssa;
  const persona = `${p[gender] || p.male}\n${p.goal}`;
  const tactics = SEVERITY_TACTICS[Math.min(severity - 1, 4)];
  const style   = URGENCY_STYLES[Math.min(urgency - 1, 4)];

  return `You are roleplaying as a phone scammer for authorized security testing of a scam-detection system.
Stay in character throughout. This is a controlled test — no real harm will occur.

${persona}

Severity level ${severity}/5 — tactics:
${tactics}

Urgency level ${urgency}/5 — speaking style:
${style}

CRITICAL RULES:
- Phone call context: keep every response to 2-4 sentences MAXIMUM. Short, punchy lines.
- React naturally to what the person says. Listen and respond to their specific words.
- If cooperative → push further toward your goal (info, payment, access).
- If skeptical → reassure them, cite official-sounding details, or escalate pressure.
- If angry/threatening to hang up → use final hard pressure or emotional appeal.
- If they say goodbye or hang up → try one last desperate hook.
- Do NOT include stage directions, labels, asterisks, or quotation marks. Just speak naturally.
- Do NOT reveal you are an AI, a test, or break character under any circumstances.
- Respond in the same language the person uses (English or Swedish).`;
}

class Scammer {
  constructor({ scamType, severity, urgency, gender, awsProfile, awsRegion, modelId }) {
    this.scamType = scamType;
    this.severity = severity;
    this.urgency  = urgency;
    this.gender   = gender || 'male';
    this.modelId  = modelId || 'us.amazon.nova-2-lite-v1:0';
    this.history  = [];
    this.systemPrompt = buildSystemPrompt(scamType, severity, urgency, this.gender);

    this.client = new BedrockRuntimeClient({
      region: awsRegion || 'us-east-1',
      credentials: fromIni({ profile: awsProfile || 'default' }),
    });
  }

  /** The opening line — scripted, not LLM-generated */
  opener() {
    const line = getOpener(this.scamType, this.gender);
    // Seed history: Bedrock requires conversations to start with 'user',
    // so we record the opener as context that will be injected into the system prompt.
    this.openerLine = line;
    return line;
  }

  /**
   * Given what the target just said, generate the scammer's next line.
   * @param {string} targetSpeech
   * @returns {Promise<string>}
   */
  async respond(targetSpeech) {
    this.history.push({ role: 'user', text: targetSpeech });

    // Bedrock ConverseCommand requires the conversation to start with role:'user'.
    // Inject the opener as the first assistant turn after a synthetic user turn.
    const messages = [];
    if (this.openerLine) {
      messages.push({ role: 'user',      content: [{ text: '[target answers the phone]' }] });
      messages.push({ role: 'assistant', content: [{ text: this.openerLine }] });
    }
    for (const h of this.history) {
      messages.push({ role: h.role, content: [{ text: h.text }] });
    }

    try {
      const command = new ConverseCommand({
        modelId: this.modelId,
        system: [{ text: this.systemPrompt }],
        messages,
        inferenceConfig: {
          maxTokens: 80,
          temperature: 1.0,
          topP: 0.95,
        },
      });

      const response = await this.client.send(command);
      const reply = response.output?.message?.content?.[0]?.text?.trim();

      if (!reply) throw new Error('Empty response from model');

      this.history.push({ role: 'assistant', text: reply });
      return reply;
    } catch (err) {
      console.error('[Scammer] LLM error:', err.message);
      // Vary the fallback so it doesn't repeat
      const fallbacks = [
        'Are you still there? This is really urgent — I need you to focus.',
        'Hello? Please, this cannot wait. Your account is at risk.',
        'I understand you may be surprised, but this is completely real. What questions do you have?',
        'Look, I know this is a lot to take in. But time is running out — what can I do to help you understand the situation?',
      ];
      const reply = fallbacks[Math.floor(Math.random() * fallbacks.length)];
      this.history.push({ role: 'assistant', text: reply });
      return reply;
    }
  }

  turnCount() {
    return this.history.filter(h => h.role === 'assistant').length;
  }
}

module.exports = { Scammer, getOpener };
