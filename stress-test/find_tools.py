import subprocess
# Find mvn location
r = subprocess.run('where mvn', shell=True, capture_output=True, text=True)
print('mvn:', r.stdout)
r2 = subprocess.run('where java', shell=True, capture_output=True, text=True)
print('java:', r2.stdout)
