import subprocess
# Kill java processes with RAGGG in command line
result = subprocess.run('wmic process where "name=\'java.exe\'" get ProcessId,CommandLine',
    capture_output=True, text=True, shell=True)
for line in result.stdout.split('\n'):
    if 'RAGGG' in line or 'rag' in line.lower():
        parts = line.strip().split()
        if parts:
            pid = parts[0]
            print(f'Killing PID: {pid}')
            subprocess.run(f'taskkill /F /PID {pid}', shell=True)
print('Done')
