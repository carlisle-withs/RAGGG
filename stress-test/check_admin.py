import pymysql
conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456', database='rag_system')
cur = conn.cursor()
cur.execute("SELECT username, password FROM t_user WHERE username='admin'")
print('admin users:', cur.fetchall())
conn.close()
