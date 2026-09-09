import urllib.request
try:
    req = urllib.request.Request('http://localhost:8081/api/v1/auth/login',
        data=b'{"username":"admin","password":"admin"}',
        headers={'Content-Type': 'application/json'})
    resp = urllib.request.urlopen(req, timeout=3)
    print('Status:', resp.status)
    import json
    print('Body:', json.loads(resp.read()))
except Exception as e:
    print('Error:', type(e).__name__, e)
