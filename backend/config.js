require('dotenv').config();

module.exports = {
  twilio: {
    accountSid: process.env.TWILIO_ACCOUNT_SID,
    authToken: process.env.TWILIO_AUTH_TOKEN,
    phoneNumber: process.env.TWILIO_PHONE_NUMBER,
  },
  userPhoneNumber: process.env.USER_PHONE_NUMBER,
  aws: {
    profile: process.env.AWS_PROFILE || 'default',
    region: process.env.AWS_REGION || 'us-east-1',
  },
  server: {
    port: parseInt(process.env.PORT || '17541', 10),
    baseUrl: process.env.BASE_URL || 'http://localhost:17541',
  },
  scamThreshold: parseInt(process.env.SCAM_THRESHOLD || '70', 10),
  smsScamThreshold: parseInt(process.env.SMS_SCAM_THRESHOLD || process.env.SCAM_THRESHOLD || '70', 10),
  models: {
    sonic: process.env.NOVA_SONIC_MODEL || 'amazon.nova-sonic-v1:0',
    lite: process.env.NOVA_LITE_MODEL || 'global.amazon.nova-2-lite-v1:0',
  },
};
