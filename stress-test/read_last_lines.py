import json
path = r'C:\Users\15234\AppData\Roaming\CherryStudio\.claude\projects\C--Users-15234-AppData-Roaming-CherryStudio-Data-Agents-w-default\813d0c49-7b45-4cc3-a57a-5cc101be6b89\tool-results\mcp-desktop-commander-start_process-1776705628879.txt'
with open(path, encoding='utf-8') as f:
    content = f.read()
lines = content.split('\n')
print(f'Total lines: {len(lines)}')
print('--- Last 60 lines ---')
for line in lines[-60:]:
    print(line)
