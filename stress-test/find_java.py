import subprocess, re
# Find all java processes with PID and command line
proc = subprocess.Popen(
    'wmic process where "name=\'java.exe\'" get ProcessId,CommandLine',
    shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
)
out, err = proc.communicate(timeout=10)
print(out[:3000])
