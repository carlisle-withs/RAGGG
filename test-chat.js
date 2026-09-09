const http = require('http');

function request(options, postData) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => { resolve({ status: res.statusCode, body }); });
    });
    req.on('error', reject);
    req.write(postData);
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
  console.log('=== 登录结果 ===');
  console.log('Status:', loginRes.status);
  console.log('Body:', loginRes.body);

  let token = null;
  try {
    const loginJson = JSON.parse(loginRes.body);
    token = loginJson.data?.token || loginJson.token;
    console.log('Token:', token);
  } catch (e) {
    console.log('解析登录响应失败:', e.message);
    return;
  }

  if (!token) {
    console.log('没有获取到token，终止测试');
    return;
  }

  // 2. Chat
  const chatData = JSON.stringify({ message: '你好，这个系统是做什么的？', conversationId: 'test-session-001' });
  const chatRes = await request({
    hostname: 'localhost', port: 8080, path: '/api/v1/chat',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(chatData),
      'Authorization': 'Bearer ' + token
    }
  }, chatData);
  console.log('\n=== 对话结果 ===');
  console.log('Status:', chatRes.status);
  console.log('Body:', chatRes.body);
}

main().catch(console.error);
