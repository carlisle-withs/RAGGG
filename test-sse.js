const http = require('http');

function request(options, postData) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => { resolve({ status: res.statusCode, headers: res.headers, body }); });
    });
    req.on('error', reject);
    if (postData) { req.write(postData); }
    req.end();
  });
}

async function main() {
  // 1. Login
  const loginData = JSON.stringify({ username: 'admin', password: 'admin123' });
  const loginRes = await request({
    hostname: 'localhost', port: 8080, path: '/api/v1/auth/login',
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(loginData) }
  }, loginData);
  console.log('=== 登录 ===');
  console.log('Status:', loginRes.status);
  let token;
  try { token = JSON.parse(loginRes.body).data?.token || JSON.parse(loginRes.body).token; }
  catch (e) { console.log('登录失败:', loginRes.body); return; }
  console.log('Token获取成功\n');

  // 2. SSE Chat
  console.log('=== SSE 对话测试 ===');
  return new Promise((resolve) => {
    const req = http.request({
      hostname: 'localhost', port: 8080,
      path: '/api/v1/chat?question=hello&conversationId=test-001',
      method: 'GET',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Accept': 'text/event-stream'
      }
    }, (res) => {
      console.log('Status:', res.statusCode);
      console.log('Content-Type:', res.headers['content-type']);
      let count = 0;
      res.on('data', (chunk) => {
        const text = chunk.toString();
        console.log('[SSE]', text.substring(0, 200));
        count++;
        if (count > 5) { res.destroy(); resolve(); }
      });
      res.on('end', () => { console.log('流结束，总共收到', count, '个事件块'); resolve(); });
      res.on('error', (e) => { console.log('流错误:', e.message); resolve(); });
    });
    req.on('error', (e) => { console.log('请求错误:', e.message); resolve(); });
    req.end();
  });
}

main().then(() => console.log('\n测试完成'));
