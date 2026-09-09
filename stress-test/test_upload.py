import urllib.request, json, os, io

# 1. Login
req = urllib.request.Request('http://localhost:8081/api/v1/auth/login',
    data=b'{"username":"admin","password":"admin"}',
    headers={'Content-Type': 'application/json'})
resp = urllib.request.urlopen(req, timeout=3)
data = json.loads(resp.read())
token = data['token']
role = data['user']['role']
print(f'Logged in as admin, role={role}')

# 2. Try upload
file_path = r'D:\Workspace\RAGGG\stress-test\real-docs\字段名称.docx'
file_name = os.path.basename(file_path)
boundary = '----FormBoundary7MA4YWxkTrZu0gW'

body = io.BytesIO()
for name, value in [('kbId', '2'), ('chunkStrategy', 'fixed')]:
    body.write(f'--{boundary}\r\n'.encode())
    body.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
    body.write(str(value).encode() + b'\r\n')
body.write(f'--{boundary}\r\n'.encode())
body.write(f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'.encode())
body.write(b'Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n')
body.write(b'\r\n')
with open(file_path, 'rb') as f:
    body.write(f.read())
body.write(b'\r\n')
body.write(f'--{boundary}--\r\n'.encode())

req2 = urllib.request.Request(
    'http://localhost:8081/api/v1/documents/upload',
    data=body.getvalue(),
    headers={
        'Content-Type': f'multipart/form-data; boundary={boundary}',
        'Authorization': f'Bearer {token}'
    }
)
try:
    resp2 = urllib.request.urlopen(req2, timeout=15)
    print('Upload Status:', resp2.status)
    print('Body:', json.loads(resp2.read()))
except urllib.error.HTTPError as e:
    print('Upload Error:', e.code)
    err_body = e.read()
    print('Body:', err_body)
except Exception as e:
    print('Error:', type(e).__name__, e)
