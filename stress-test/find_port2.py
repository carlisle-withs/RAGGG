import subprocess

# Get listening ports
proc = subprocess.Popen('netstat -ano', shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
out, _ = proc.communicate()
for line in out.split('\n'):
    if 'LISTENING' in line and ':808' in line:
        print(line.strip())
