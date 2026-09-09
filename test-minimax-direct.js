const https = require('https');

const token = 'sk-cp-GZiEUROmyANxyr0sL20NVZeCQHUoivuZo0GXEAA6B55Ob6C5aCxXL2jKz2ELKsLXkdCJN-P8ANj-681kzpmeyL1Vj7EEbLfIlLgBcYmJlG-i53b94nUfpdY';
const groupId = '2034153629136458495';

const body = JSON.stringify({
  model: 'MiniMax-M2.7',
  stream: true,
  role_meta: {
    user_name: 'user',
    bot_name: 'AI'
  },
  messages: [{
    role: 'user',
    content: 'Hello, who are you?'
  }]
});

const options = {
  hostname: 'api.minimax.chat',
  port: 443,
  path: '/v1/text/chatcompletion_v2',
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
    'group_id': groupId
  }
};

const req = https.request(options, (res) => {
  console.log('Status:', res.statusCode);
  console.log('Content-Type:', res.headers['content-type']);
  let count = 0;
  res.on('data', (chunk) => {
    const text = chunk.toString();
    console.log('Chunk[' + count + ']:', JSON.stringify(text).substring(0, 300));
    count++;
    if (count >= 10) {
      req.destroy();
    }
  });
  res.on('end', () => console.log('End'));
});

req.on('error', (e) => console.error('Error:', e.message));
req.write(body);
req.end();

setTimeout(() => { req.destroy(); process.exit(0); }, 15000);
