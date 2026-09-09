const http = require('http');

function get(url) {
  return new Promise((resolve, reject) => {
    const urlObj = new URL(url);
    const req = http.get({
      hostname: urlObj.hostname,
      port: urlObj.port,
      path: urlObj.pathname,
      timeout: 5000
    }, (res) => {
      let body = '';
      res.on('data', (chunk) => body += chunk);
      res.on('end', () => resolve({ status: res.statusCode, body: body.substring(0, 500) }));
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

(async () => {
  try {
    const r1 = await get('http://localhost:8080/swagger-ui/index.html');
    console.log('Swagger UI:', r1.status, 'OK - body:', r1.body.includes('swagger-ui') ? 'contains swagger-ui' : r1.body.substring(0, 100));
  } catch(e) { console.log('Swagger UI error:', e.message); }

  try {
    const r2 = await get('http://localhost:8080/v3/api-docs');
    console.log('OpenAPI JSON:', r2.status, 'OK - body preview:', r2.body.substring(0, 200));
  } catch(e) { console.log('OpenAPI error:', e.message); }
})();
