import subprocess
# Find java processes listening on 8081
result = subprocess.run('netstat -ano | findstr ":8081"', shell=True, capture_output=True, text=True)
print('netstat result:', result.stdout)
print('stderr:', result.stderr)
