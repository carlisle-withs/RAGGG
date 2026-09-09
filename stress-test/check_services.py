import socket

def port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

for port, name in [(9090, 'Prometheus'), (49090, 'agentflow-prometheus'), (43002, 'Grafana')]:
    status = "IN USE" if port_in_use(port) else "free"
    print(f"Port {port} ({name}): {status}")
