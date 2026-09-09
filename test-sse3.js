const http = require('http');

async function post(url, body) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const data = JSON.stringify(body);
    const req = http.request({
      hostname: urlObj.hostname,
      port: urlObj.port,
      path: urlObj.pathname,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
    }, (res) => {
      let body = '';
      res.on('data', (chunk) => body += chunk);
      res.on('end', () => resolve({ status: res.statusCode, data: JSON.parse(body) }));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function getStream(url, headers) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const req = http.get({
      hostname: urlObj.hostname,
      port: urlObj.port,
      path: urlObj.pathname + urlObj.search,
      headers
    }, (res) => {
      console.log('Status:', res.statusCode);
      console.log('Content-Type:', res.headers['content-type']);
      let count = 0;
      res.on('data', (chunk) => {
        const text = chunk.toString();
        console.log('SSE data[' + count + ']:', JSON.stringify(text).substring(0, 200));
        count++;
        if (count >= 15) {
          req.destroy();
          resolve();
        }
      });
      res.on('end', () => { console.log('Stream ended'); resolve(); });
      res.on('error', reject);
    });
    req.on('error', reject);
    req.setTimeout(30000, () => { console.log('Timeout!'); req.destroy(); resolve(); });
  });
}

async function test() {
  // Login
  const login = await post('http://localhost:8080/api/v1/auth/login', {
    username: 'admin', password: 'admin123'
  });
  console.log('Login status:', login.status);
  const token = login.data.token;
  console.log('Token:', token.substring(0, 50) + '...');

  // Test SSE chat
  console.log('\n--- Testing SSE chat ---');
  await getStream('http://localhost:8080/api/v1/chat?question=你好', {
    Authorization: `Bearer ${token}`
  });
}

test().catch(console.error);
