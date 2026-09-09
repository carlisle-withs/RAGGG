const http = require('http');

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({
      hostname: 'localhost', port: 8080, path,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
    }, res => { let b = ''; res.on('data', c => b += c); res.on('end', () => resolve(JSON.parse(b))); });
    req.on('error', reject); req.write(data); req.end();
  });
}

async function main() {
  const login = await post('/api/v1/auth/login', { username: 'admin', password: 'admin123' });
  const token = login.token;
  console.log('Token:', token ? 'Got' : 'MISSING');
  if (!token) { console.log(login); return; }

  return new Promise((resolve) => {
    const req = http.request({
      hostname: 'localhost', port: 8080,
      path: '/api/v1/chat?question=hi&conversationId=test-001',
      method: 'GET',
      headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'text/event-stream' }
    }, res => {
      let chunks = [];
      res.on('data', chunk => {
        chunks.push(chunk.toString());
        process.stdout.write('.');
      });
      res.on('end', () => {
        console.log('\n\n=== Full SSE ===');
        console.log(chunks.join('').substring(0, 3000));
        resolve();
      });
      // timeout after 30s
      setTimeout(() => {
        console.log('\n\n=== Partial SSE (timeout) ===');
        console.log(chunks.join('').substring(0, 3000));
        resolve();
      }, 30000);
    });
    req.on('error', e => { console.log('Req Error:', e.message); resolve(); });
    req.end();
  });
}

main().then(() => console.log('Done'));
