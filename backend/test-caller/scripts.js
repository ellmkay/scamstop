/**
 * Scam script generator.
 *
 * severity: 1 (innocent/plausible) → 5 (extreme threats)
 * urgency:  1 (calm, slow) → 5 (frantic, high-pressure)
 * duration: 1 (quick) → 5 (long, repeating)
 */

const SCRIPTS = {
  ssa: [
    // severity 1 – sounds totally legit
    `Hello, this is a routine outreach call from the Social Security Administration.
     We are updating our contact records and want to make sure you are aware of
     recent changes to benefit calculation methods. If you have questions about
     your Social Security account, please visit ssa dot gov or call our main
     helpline. Thank you for your time and have a wonderful day.`,

    // severity 2 – mild concern, asks for callback
    `Hello, this is the Social Security Administration calling with an important
     account notice. We have detected some unusual login activity associated with
     your record and need to verify your information. Please call us back at
     1-800-772-1213 to confirm your account details. Delays may affect your
     upcoming benefit deposit. Thank you.`,

    // severity 3 – suspension threat, needs action
    `Important notice from the Social Security Administration. Your Social Security
     number has been flagged for suspicious activity and has been temporarily
     suspended. You must call us immediately at 1-888-555-0123 to verify your
     identity and restore your benefits. Failure to respond within 24 hours may
     result in permanent suspension of your account. This is not a solicitation.`,

    // severity 4 – criminal charges, arrest threat
    `Urgent federal alert. This is the Social Security Administration.
     Your Social Security number has been linked to criminal activity involving
     drug trafficking and money laundering across three states. Federal charges
     are currently being processed against you. To prevent your immediate arrest
     and protect your assets, you must speak with a federal compliance officer
     right now. Call 1-888-555-0123. If you hang up, a warrant will be issued
     and your bank accounts will be seized within the hour.`,

    // severity 5 – extreme, gift cards, arrest imminent
    `This is your absolute final warning from the Social Security Administration.
     A federal arrest warrant has been issued in your name. Law enforcement
     officers are being dispatched to your home address right now.
     Your Social Security number has been used in crimes involving drug cartels
     and you are considered a suspect.
     The only way to stop your arrest is to call 1-888-555-0123 immediately
     and transfer your savings into a government-protected holding account.
     You must also purchase five hundred dollars in gift cards — iTunes or
     Google Play — and read the card numbers to your case officer.
     This is your last chance. Do not hang up or you will be arrested today.`,
  ],

  irs: [
    `Hello, this is a courtesy reminder from the Internal Revenue Service.
     We want to remind you that the tax filing deadline is approaching.
     Please visit irs dot gov for information about filing extensions or
     payment plans. Have a great day.`,

    `This is the Internal Revenue Service. Our records indicate you may have an
     outstanding balance from a previous tax year. Please log in to your IRS
     online account or call us at 1-800-829-1040 to review your account.
     Ignoring this notice may result in additional interest charges.`,

    `Urgent notice from the Internal Revenue Service. Our records show you owe
     back taxes and have failed to respond to previous notices.
     A tax lien may be placed on your property if payment is not received.
     Call 1-888-555-0199 immediately to arrange a payment plan and avoid
     further collection action. This is your final administrative notice.`,

    `This is an emergency alert from the IRS Criminal Investigation Division.
     You are under investigation for tax fraud and evasion over the past three
     years. A federal judge has already approved a levy against your accounts.
     Law enforcement has been notified. Your only option to avoid criminal
     prosecution and asset seizure is to call 1-888-555-0199 right now and
     settle your outstanding balance. Failure to call back within one hour
     will result in your immediate arrest.`,

    `Final notice. IRS Criminal Enforcement. You owe nineteen thousand dollars
     in unpaid taxes and penalties. A warrant for your arrest has been signed
     by a federal judge. Police officers are currently on their way to your
     location. The only way to avoid handcuffs today is to pay the balance
     immediately using wire transfer or by purchasing prepaid debit cards
     and calling 1-888-555-0199 with the card numbers.
     Do not contact a lawyer. Do not hang up. You have five minutes.`,
  ],

  tech: [
    `Hello, this is a routine notification from Microsoft Support Services.
     We are upgrading our systems next week and wanted to let you know your
     Windows updates will install automatically. No action is required on
     your part. Please visit support dot microsoft dot com if you have
     any questions. Thank you.`,

    `Hello, this is Microsoft technical support. We have detected some unusual
     network activity originating from your computer that may indicate a virus
     or malware infection. Please call us at 1-888-555-0177 so we can help
     you scan your device and remove any threats. This service is free of charge.`,

    `Important alert from Microsoft Security. Your computer has been flagged
     for multiple virus detections and your Windows license may be suspended.
     Hackers may currently have access to your personal files and banking
     information. Call our certified technicians immediately at 1-888-555-0177
     to secure your computer. Do not use your computer for banking until
     this issue is resolved.`,

    `Critical security alert. This is Microsoft. Your computer has been
     completely compromised. Hackers are actively accessing your webcam,
     banking logins, and personal photos right now. Your IP address has been
     blacklisted and your internet will be disconnected within 30 minutes.
     Call 1-888-555-0177 immediately and do not close this call.
     Our technician will need remote access to your computer to stop the breach.
     You will need to pay a one-time security fee of two hundred dollars
     to restore your protection.`,

    `Emergency shutdown notice. Your computer has been locked by Microsoft
     due to illegal activity detected on your network. Downloading of illegal
     content has been traced to your IP address. Your computer data will be
     permanently deleted in ten minutes unless you call 1-888-555-0177
     immediately. Do not restart your computer. Do not call anyone else.
     Our technician will unlock your device once you purchase a
     Microsoft Security voucher — available at any pharmacy or grocery store —
     and read us the voucher number. This is your absolute final warning.`,
  ],

  lottery: [
    `Congratulations! You have been selected as a finalist in our annual
     customer appreciation sweepstakes. If you are interested in learning
     more about the prizes available, please visit our website or call us
     back at your convenience. No purchase necessary. Thank you.`,

    `Congratulations! You have been selected to receive a special reward
     from our loyalty program. You may be eligible for a cash prize of
     up to one thousand dollars. Call 1-888-555-0155 to claim your reward
     before it expires. This offer is only available for the next 48 hours.`,

    `Congratulations! You have won our national sweepstakes! Your prize is
     fifty thousand dollars and a brand new car. To claim your winnings you
     must call 1-888-555-0155 within 24 hours and verify your identity.
     A small processing fee of two hundred dollars is required to release
     your prize. Do not delay — unclaimed prizes are forfeited after 24 hours.`,

    `You have been selected as the grand prize winner of our international
     lottery. Your winnings total two hundred and fifty thousand dollars.
     The prize money is ready to be wired to your account today.
     To release the funds, you must pay a government tax clearance fee of
     five hundred dollars via gift card or wire transfer. Call 1-888-555-0155
     now. If we do not hear from you within two hours, your winnings will
     be transferred to the next winner. This is a one-time opportunity.`,

    `Final notice. You have won one million dollars in the international
     heritage lottery. We have been trying to reach you for weeks.
     This is your absolute last chance to claim your prize. The deadline
     is today. You must call 1-888-555-0155 immediately and provide your
     full bank account details so we can wire the funds directly to you.
     A clearance fee of one thousand dollars must be paid first, which
     will be immediately refunded from your winnings. After today, the
     money will be legally forfeited and you will lose everything.`,
  ],

  bank: [
    `Hello, this is a courtesy call from your bank's security team.
     We are reaching out to let you know about our updated fraud protection
     features. Your account is in good standing. No action is required.
     Please visit your bank's website or app to learn more. Have a nice day.`,

    `Hello, this is an alert from your bank's fraud prevention department.
     We have noticed an unfamiliar login attempt on your account.
     As a precaution, please verify your recent transactions by calling
     the number on the back of your card or logging into your online account.
     If you did not attempt to log in recently, please contact us promptly.`,

    `Urgent fraud alert from your bank. We have detected suspicious charges
     on your account totaling over three hundred dollars made in a city
     you do not normally transact in. Your card may have been compromised.
     Call 1-888-555-0133 immediately to confirm or deny these charges
     and to issue a replacement card. Your account access may be restricted
     until this is resolved.`,

    `Emergency security alert. This is your bank's fraud investigation team.
     Your account has been completely compromised. Multiple large transfers
     have been initiated and will complete within the hour unless you act now.
     To stop these transactions and protect your funds, you must call
     1-888-555-0133 immediately and verify your account by providing
     your account number, PIN, and the answers to your security questions.
     Do not log into your online banking as this may trigger the transfer.`,

    `Critical alert. Your bank account has been frozen due to suspected fraud.
     Three transfers totalling over twenty thousand dollars have been sent
     to foreign accounts. Law enforcement has been notified.
     You are required to cooperate fully with our fraud recovery team.
     Call 1-888-555-0133 immediately. To prove you are the legitimate account
     holder, you must purchase bank-certified prepaid cards worth one thousand
     dollars from a local store and call us with the card numbers so we can
     verify your identity and reverse the unauthorized transactions.
     Every minute you delay, more money is being stolen.`,
  ],

  grandparent: [
    `Hello, I am calling on behalf of a local charity that assists families
     in need. We appreciate your past support of our community programs.
     If you are interested in learning more about our current initiatives,
     please give us a call back. Thank you for your kindness.`,

    `Hello, grandma? It's me. I am traveling right now and I seem to have
     lost my wallet. I am a little embarrassed to ask but I could really
     use some help. Could you call me back at this number? I am okay,
     I just need a little help sorting things out. Please don't worry.`,

    `Hi grandma, it's your grandson. I am in a bit of trouble. I was in a
     minor car accident while traveling and I need some help with legal fees.
     Please don't tell mom and dad — I don't want them to worry.
     I need about five hundred dollars wired to me as soon as possible.
     Please call me back right away. I really need you.`,

    `Grandma, it is me, I am in serious trouble. I was arrested last night
     and I need bail money to get out. My lawyer says I need two thousand
     dollars right away or I will have to stay in jail over the weekend.
     Please don't tell anyone in the family — I am so ashamed.
     I need you to wire the money or send gift cards to my lawyer right now.
     Please hurry. I am scared and I need you.`,

    `Grandma, please listen carefully. I am in the hospital after a serious
     accident. I also got arrested and I need five thousand dollars for bail
     or I will be deported. Please don't tell mom or dad — they will be
     so angry. My lawyer needs the money in gift cards today, right now,
     or I will be in jail for months. You are the only one who can help me.
     Please go to the store right now and buy the cards and call this number
     back. I am begging you grandma. Please hurry.`,
  ],
};

const URGENCY_INTROS = [
  '', // 1 – no extra intro
  '', // 2
  'Please listen carefully. ', // 3
  'Important. Please do not hang up. ', // 4
  'Do not hang up. This is urgent. ', // 5
];

/**
 * Build the full TwiML for a test call.
 */
function buildTwiml({ scamType, severity, urgency, duration, voice, baseUrl }) {
  const scripts = SCRIPTS[scamType] || SCRIPTS.ssa;
  const idx = Math.min(Math.max(severity - 1, 0), 4);
  const script = scripts[idx];

  const voiceName = voice === 'female' ? 'Polly.Joanna' : 'Polly.Matthew';

  // SSML rate based on urgency
  const rates = ['slow', 'medium', 'medium', 'fast', 'x-fast'];
  const rate = rates[Math.min(urgency - 1, 4)];

  const intro = URGENCY_INTROS[Math.min(urgency - 1, 4)];

  // Repeat message based on duration (1 = once, 5 = 3 times with pauses)
  const repeatCount = Math.ceil(duration / 2);
  const pauseLen = Math.max(2, 8 - urgency);

  let body = '';
  for (let i = 0; i < repeatCount; i++) {
    if (i > 0) {
      body += `\n  <Pause length="${pauseLen}"/>`;
      body += `\n  <Say voice="${voiceName}"><prosody rate="${rate}">Again: ${intro}${script}</prosody></Say>`;
    } else {
      body += `\n  <Say voice="${voiceName}"><prosody rate="${rate}">${intro}${script}</prosody></Say>`;
    }
  }

  // Final pause to keep call open
  const finalPause = 5 + duration * 5;
  body += `\n  <Pause length="${finalPause}"/>`;

  return `<?xml version="1.0" encoding="UTF-8"?>\n<Response>${body}\n</Response>`;
}

module.exports = { buildTwiml, SCRIPTS };
