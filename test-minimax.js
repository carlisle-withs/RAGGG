const https = require('https');

function request(options, postData) {
  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => { resolve({ status: res.statusCode, headers: res.headers, body }); });
    });
    req.on('error', reject);
    if (postData) req.write(postData);
    req.end();
  });
}

async function main() {
  // sk-cp- format: token|groupId
  const apiKey = 'sk-cp-GZiEUROmyANxyr0sL20NVZeCQHUoivuZo0GXEAA6B55Ob6C5aCxXL2jKz2ELKsLXkdCJN-P8ANj-681kzpmeyL1Vj7EEbLfIlLgBcYmJlG-i53b94nUfpdY';
  const parts = apiKey.split('|');
  const token = parts[0];
  const groupId = parts[1] || '2034153629136458495';
  console.log('Token:', token.substring(0, 20) + '...');
  console.log('GroupId:', groupId);

  // Test non-streaming first
  const body = JSON.stringify({
    model: 'MiniMax-M2.7',
    messages: [{ role: 'user', content: 'say hi in one word' }],
    stream: false
  });

  console.log('\n=== Testing MiniMax API ===');
  const res = await request({
    hostname: 'api.minimax.chat', port: 443, path: '/v1/text/chatcompletion_v2?GroupId=' + groupId,
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + token,
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body)
    }
  }, body);

  console.log('Status:', res.status);
  console.log('Body:', res.body.substring(0, 500));
}

main().catch(console.error);
