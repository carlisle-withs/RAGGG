import pymysql
conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456', database='rag_system')
cur = conn.cursor()
cur.execute('SELECT id, username, role FROM t_user')
print('Users:', cur.fetchall())
cur.execute('SELECT id, name, created_by FROM t_knowledge_base')
print('KBs:', cur.fetchall())
conn.close()
