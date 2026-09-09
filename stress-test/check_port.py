import socket

def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

for port in [8081, 8080, 48081, 8082, 8083]:
    status = "IN USE" if port_in_use(port) else "free"
    print(f"Port {port}: {status}")
