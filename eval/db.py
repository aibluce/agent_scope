#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SQL 执行工具（对应 RAGAS 官方示例里的 db_utils.execute_sql）。

把查询结果转成 pandas DataFrame，交给 datacompy 做结果集比对。
数据库连接信息可用环境变量覆盖：T2SQL_DB_HOST / PORT / USER / PASSWORD / NAME。
"""
import os
import pandas as pd

DB_CONFIG = {
    "host": os.getenv("T2SQL_DB_HOST", "127.0.0.1"),
    "port": int(os.getenv("T2SQL_DB_PORT", "3306")),
    "user": os.getenv("T2SQL_DB_USER", "root"),
    "password": os.getenv("T2SQL_DB_PASSWORD", "Atguigu.123"),
    "database": os.getenv("T2SQL_DB_NAME", "ecommerce"),
    "charset": "utf8mb4",
}


def execute_sql(sql, max_rows=2000):
    """
    执行一条只读 SQL。

    :return: (success: bool, DataFrame | error_message)
    """
    try:
        import pymysql
        conn = pymysql.connect(cursorclass=pymysql.cursors.Cursor, read_timeout=60, **DB_CONFIG)
        try:
            with conn.cursor() as cur:
                cur.execute(sql)
                columns = [d[0] for d in (cur.description or [])]
                rows = cur.fetchmany(max_rows) if columns else []
            return True, pd.DataFrame(list(rows), columns=columns)
        finally:
            conn.close()
    except Exception as e:
        return False, "SQL execution failed: %s: %s" % (type(e).__name__, e)
