import base64, json
# Get token from login
import urllib.request
req = urllib.request.Request('http://localhost:8081/api/v1/auth/login', 
    data=json.dumps({'username':'admin','password':'admin'}).encode(),
    headers={'Content-Type':'application/json'})
resp = urllib.request.urlopen(req, timeout=5)
data = json.loads(resp.read())
token = data.get('data',{}).get('token') or data.get('token')
print('Token:', token[:30], '...')
# JWT has 3 parts: header.payload.signature
parts = token.split('.')
payload_b64 = parts[1]
# Add padding
padding = 4 - len(payload_b64) % 4
if padding < 4:
    payload_b64 += '=' * padding
payload_bytes = base64.urlsafe_b64decode(payload_b64)
payload = json.loads(payload_bytes)
print('Payload:', json.dumps(payload, indent=2))
print('Role claim:', payload.get('role'))
