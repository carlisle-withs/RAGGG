import urllib.request, json

# Try localhost:8081 - should be the RAGGG backend after our fix
try:
    req = urllib.request.Request('http://localhost:8081/api/v1/auth/login',
        data=b'{"username":"admin","password":"admin"}',
        headers={'Content-Type': 'application/json'})
    resp = urllib.request.urlopen(req, timeout=3)
    data = json.loads(resp.read())
    token = data['token']
    print('8081 Token OK, role:', data.get('user', {}).get('role'))
except Exception as e:
    print('8081 Error:', type(e).__name__, str(e)[:100])

# Try 8080
try:
    req = urllib.request.Request('http://localhost:8080/api/v1/auth/login',
        data=b'{"username":"admin","password":"admin"}',
        headers={'Content-Type': 'application/json'})
    resp = urllib.request.urlopen(req, timeout=3)
    data = json.loads(resp.read())
    token = data['token']
    print('8080 Token OK, role:', data.get('user', {}).get('role'))
except Exception as e:
    print('8080 Error:', type(e).__name__, str(e)[:100])
