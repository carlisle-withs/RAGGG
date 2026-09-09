import os
docs = r'D:\Workspace\RAGGG\stress-test\data\crud_rag\docs'
for f in sorted(os.listdir(docs)):
    p = os.path.join(docs, f)
    print(f'{f}: {os.path.getsize(p)//1024}KB')