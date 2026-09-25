#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成大规模测试数据（默认 10 万订单），用于性能测试。

口径与 data.sql 完全一致：
  * t_order.total_amount = 明细金额之和；item_count = 明细数量之和；pay_amount = total - discount
  * 成交口径 order_status IN (2,3,4) 用于回填 t_user.total_amount 与 t_product.sales_count
  * 订单时间覆盖最近 N 天（默认 730 天），近期下单更密集，便于测时间窗口查询

用法：
    eval/.venv/bin/python scripts/gen-large-data.py                       # 10 万订单
    eval/.venv/bin/python scripts/gen-large-data.py --orders 1000000      # 100 万
    eval/.venv/bin/python scripts/gen-large-data.py --keep-existing       # 追加而不是重建
    MYSQL_PASSWORD=xxx eval/.venv/bin/python scripts/gen-large-data.py
"""
import argparse
import os
import random
import time
from datetime import date, datetime, timedelta

import pymysql

def _load_dotenv(path=".env"):
    """极简 .env 加载：KEY=VALUE 逐行读入环境变量（不覆盖已存在的）。"""
    import os as _os
    for candidate in (path, _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))), ".env")):
        if _os.path.isfile(candidate):
            for line in open(candidate, encoding="utf-8"):
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                _os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))
            break


_load_dotenv()


DB = dict(
    host=os.getenv("T2SQL_DB_HOST", "127.0.0.1"),
    port=int(os.getenv("T2SQL_DB_PORT", "3306")),
    user=os.getenv("T2SQL_DB_USER", "root"),
    password=os.getenv("T2SQL_DB_PASSWORD", "root"),
    database=os.getenv("T2SQL_DB_NAME", "ecommerce"),
    charset="utf8mb4",
)

PROVINCES = [
    ("110000", "北京市", "北京", "华北"), ("310000", "上海市", "上海", "华东"),
    ("440000", "广东省", "广东", "华南"), ("330000", "浙江省", "浙江", "华东"),
    ("320000", "江苏省", "江苏", "华东"), ("510000", "四川省", "四川", "西南"),
    ("420000", "湖北省", "湖北", "华中"), ("370000", "山东省", "山东", "华东"),
    ("610000", "陕西省", "陕西", "西北"), ("350000", "福建省", "福建", "华东"),
    ("500000", "重庆市", "重庆", "西南"), ("230000", "黑龙江省", "黑龙江", "东北"),
    ("410000", "河南省", "河南", "华中"), ("430000", "湖南省", "湖南", "华中"),
]
CITIES = {
    "北京市": ["朝阳区", "海淀区", "丰台区", "通州区"], "上海市": ["浦东新区", "徐汇区", "静安区", "闵行区"],
    "广东省": ["深圳市", "广州市", "东莞市", "佛山市"], "浙江省": ["杭州市", "宁波市", "温州市", "嘉兴市"],
    "江苏省": ["南京市", "苏州市", "无锡市", "常州市"], "四川省": ["成都市", "绵阳市", "德阳市", "宜宾市"],
    "湖北省": ["武汉市", "宜昌市", "襄阳市", "黄石市"], "山东省": ["济南市", "青岛市", "烟台市", "潍坊市"],
    "陕西省": ["西安市", "咸阳市", "宝鸡市", "渭南市"], "福建省": ["福州市", "厦门市", "泉州市", "漳州市"],
    "重庆市": ["渝中区", "江北区", "南岸区", "沙坪坝区"], "黑龙江省": ["哈尔滨市", "大庆市", "齐齐哈尔市", "牡丹江市"],
    "河南省": ["郑州市", "洛阳市", "南阳市", "新乡市"], "湖南省": ["长沙市", "株洲市", "湘潭市", "衡阳市"],
}
CATALOG = [
    ("手机数码", "华为", "华为 Mate 60 Pro 12+512G", 6999.00, 5580.00),
    ("手机数码", "小米", "小米 14 Pro 16+512G", 5499.00, 4320.00),
    ("手机数码", "苹果", "Apple iPhone 15 128G", 5999.00, 4980.00),
    ("手机数码", "荣耀", "荣耀 Magic6 16+256G", 4399.00, 3450.00),
    ("手机数码", "一加", "一加 Ace 3 16+512G", 2599.00, 2050.00),
    ("家用电器", "美的", "美的变频空调 1.5匹", 2799.00, 2150.00),
    ("家用电器", "海尔", "海尔 501L 十字对开门冰箱", 3299.00, 2560.00),
    ("家用电器", "小米", "小米空气净化器 4 Pro", 1499.00, 1080.00),
    ("家用电器", "格力", "格力落地电风扇", 299.00, 190.00),
    ("家用电器", "戴森", "戴森 V12 无线吸尘器", 3990.00, 3120.00),
    ("服饰鞋包", "优衣库", "优衣库男士摇粒绒外套", 299.00, 150.00),
    ("服饰鞋包", "耐克", "耐克 Air Force 1 板鞋", 799.00, 480.00),
    ("服饰鞋包", "李宁", "李宁运动连帽卫衣", 329.00, 175.00),
    ("服饰鞋包", "太平鸟", "太平鸟女士风衣", 699.00, 330.00),
    ("服饰鞋包", "新秀丽", "新秀丽商务双肩包", 899.00, 520.00),
    ("食品生鲜", "三只松鼠", "三只松鼠坚果大礼包 1558g", 129.00, 78.00),
    ("食品生鲜", "伊利", "伊利金典纯牛奶 250ml*12", 69.00, 46.00),
    ("食品生鲜", "柴火大院", "柴火大院五常大米 10kg", 109.00, 68.00),
    ("食品生鲜", "蟹状元", "阳澄湖大闸蟹礼盒 4对", 399.00, 245.00),
    ("食品生鲜", "良品铺子", "良品铺子零食大礼包", 158.00, 95.00),
    ("美妆个护", "兰蔻", "兰蔻小黑瓶精华 50ml", 1080.00, 760.00),
    ("美妆个护", "雅诗兰黛", "雅诗兰黛小棕瓶精华 50ml", 1180.00, 820.00),
    ("美妆个护", "珀莱雅", "珀莱雅红宝石面霜 50g", 289.00, 150.00),
    ("美妆个护", "花西子", "花西子雕花口红", 219.00, 110.00),
    ("美妆个护", "云南白药", "云南白药牙膏 4支装", 89.00, 52.00),
    ("母婴玩具", "帮宝适", "帮宝适绿帮纸尿裤 L 码 3包", 299.00, 205.00),
    ("母婴玩具", "飞鹤", "飞鹤星飞帆奶粉 900g", 328.00, 225.00),
    ("母婴玩具", "乐高", "乐高城市系列积木 60380", 599.00, 395.00),
    ("母婴玩具", "巴布豆", "巴布豆婴儿高景观推车", 799.00, 470.00),
    ("母婴玩具", "迪士尼", "迪士尼儿童减负书包", 169.00, 85.00),
    ("图书文娱", "读客", "《三体》全集 三册", 108.00, 62.00),
    ("图书文娱", "磨铁", "《明朝那些事儿》全套 9 册", 239.00, 140.00),
    ("图书文娱", "中信", "《人类简史》精装版", 68.00, 38.00),
    ("图书文娱", "TOM", "TOM 尤克里里入门琴 23 寸", 399.00, 210.00),
    ("图书文娱", "晨光", "晨光文具开学礼盒", 89.00, 48.00),
    ("运动户外", "迪卡侬", "迪卡侬男士缓震跑步鞋", 299.00, 160.00),
    ("运动户外", "Keep", "Keep 加厚防滑瑜伽垫", 99.00, 48.00),
    ("运动户外", "骆驼", "骆驼户外防风帐篷 3-4人", 599.00, 330.00),
    ("运动户外", "安踏", "安踏男士运动套装", 459.00, 240.00),
    ("运动户外", "捷安特", "捷安特 ATX 山地自行车", 1999.00, 1450.00),
]
SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜"
GIVEN = ["伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "军", "洋", "勇", "艳", "杰", "娟",
         "涛", "明", "超", "秀英", "霞", "平", "刚", "桂英", "建华", "文博", "雨欣", "子豪"]
CHANNELS = ["APP", "APP", "小程序", "PC", "H5"]
PROMO = [1.0, 1.0, 1.0, 1.0, 0.95, 0.9, 0.85]


def batched(conn, sql, rows, batch=5000, label=""):
    """分批 executemany 写入。"""
    started = time.time()
    with conn.cursor() as cur:
        for i in range(0, len(rows), batch):
            cur.executemany(sql, rows[i:i + batch])
            conn.commit()
            print("\r   %s %d/%d" % (label, min(i + batch, len(rows)), len(rows)), end="", flush=True)
    print("\r   %s 完成：%d 行，用时 %.1fs" % (label, len(rows), time.time() - started))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--orders", type=int, default=100000)
    ap.add_argument("--users", type=int, default=5000)
    ap.add_argument("--days", type=int, default=730, help="订单时间跨度（天）")
    ap.add_argument("--seed", type=int, default=20260925)
    ap.add_argument("--keep-existing", action="store_true", help="不清空，追加数据")
    args = ap.parse_args()

    random.seed(args.seed)
    conn = pymysql.connect(**DB)
    anchor = date.today()

    with conn.cursor() as cur:
        cur.execute("SET SESSION unique_checks = 0")
        cur.execute("SET SESSION sql_mode = ''") if False else None

    if not args.keep_existing:
        print("== 清空业务数据 ==")
        with conn.cursor() as cur:
            for table in ("t_order_item", "t_order", "t_user", "t_product", "t_province"):
                cur.execute("SET FOREIGN_KEY_CHECKS = 0")
                cur.execute("TRUNCATE TABLE `%s`" % table)
        conn.commit()

    # ---------- 省份 ----------
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM t_province")
        if cur.fetchone()[0] == 0:
            cur.executemany(
                "INSERT INTO t_province (province_code, province_name, short_name, region, is_active, created_at)"
                " VALUES (%s,%s,%s,%s,1,%s)",
                [(p[0], p[1], p[2], p[3], "2024-01-01 00:00:00") for p in PROVINCES])
            conn.commit()
            print("== 省份：%d 条（已存在则跳过）" % len(PROVINCES))

    # ---------- 商品 ----------
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM t_product")
        if cur.fetchone()[0] == 0:
            rows = []
            for i, (cat, brand, name, price, cost) in enumerate(CATALOG, start=1):
                rows.append(("P%06d" % i, name, cat, brand, price, cost,
                             random.randint(0, 5000), 1, "2025-01-01 00:00:00"))
            cur.executemany(
                "INSERT INTO t_product (product_no, product_name, category_name, brand, price,"
                " cost_price, stock, status, create_time) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", rows)
            conn.commit()
            print("== 商品：%d 条" % len(rows))

    with conn.cursor() as cur:
        cur.execute("SELECT id, price, cost_price, product_name, category_name FROM t_product")
        products = cur.fetchall()
        cur.execute("SELECT id, province_name FROM t_province")
        provinces = cur.fetchall()

    # ---------- 用户 ----------
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM t_user")
        user_count = cur.fetchone()[0]
    if user_count < args.users:
        need = args.users - user_count
        rows = []
        for i in range(user_count + 1, user_count + need + 1):
            pid, pname = random.choice(provinces)
            reg = anchor - timedelta(days=random.randint(30, 1200))
            gender = random.choice([0, 1, 1, 2, 2, 2])
            rows.append((
                "U%d%05d" % (reg.year, i), "user%05d" % i,
                random.choice(SURNAMES) + random.choice(GIVEN),
                "1%d%09d" % (random.choice([3, 5, 7, 8, 9]), random.randint(0, 999999999)),
                "user%05d@example.com" % i, gender, random.randint(18, 65),
                random.choice([1, 1, 1, 2, 2, 3, 4]), pid, random.choice(CITIES[pname]),
                1 if i % 13 else 0, reg.strftime("%Y-%m-%d %H:%M:%S")))
        batched(conn, "INSERT INTO t_user (user_no, username, real_name, phone, email, gender, age,"
                      " member_level, province_id, city, status, register_time)"
                      " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", rows, label="用户写入")
        with conn.cursor() as cur:
            cur.execute("SELECT id, province_id FROM t_user")
            users = cur.fetchall()
    else:
        with conn.cursor() as cur:
            cur.execute("SELECT id, province_id FROM t_user")
            users = cur.fetchall()

    # ---------- 订单 + 明细 ----------
    print("== 生成 %d 笔订单（跨度 %d 天）==" % (args.orders, args.days))
    started = time.time()
    order_rows, item_rows = [], []
    status_pool = ([1] * 5 + [2] * 15 + [3] * 10 + [4] * 60 + [5] * 6 + [6] * 4)  # 期望分布
    user_ids = [u[0] for u in users]
    user_province = {u[0]: u[1] for u in users}
    for i in range(1, args.orders + 1):
        uid = random.choice(user_ids)
        # 近期更密集：用平方分布把时间偏向最近
        offset = int(args.days * (random.random() ** 1.6))
        ct = datetime.combine(anchor - timedelta(days=offset),
                              datetime.min.time()) + timedelta(
            hours=random.randint(0, 23), minutes=random.randint(0, 59), seconds=random.randint(0, 59))
        status = random.choice(status_pool)
        items = random.sample(products, random.randint(1, 4))
        amount = 0.0
        qty_total = 0
        for pid, price, cost, pname, cat in items:
            qty = random.randint(1, 3)
            unit = round(float(price) * random.choice(PROMO), 2)
            amount += unit * qty
            qty_total += qty
            item_rows.append((i, "SO%s%07d" % (ct.strftime("%Y%m%d"), i), pid, pname, cat,
                              unit, qty, round(unit * qty, 2), ct.strftime("%Y-%m-%d %H:%M:%S")))
        total = round(amount, 2)
        discount = round(total * random.choice([0, 0, 0, 0.05, 0.1, 0.15, 0.2]), 2)
        pay = round(total - discount, 2)
        pay_time = None if status == 1 else (ct + timedelta(minutes=random.randint(1, 240)))
        order_rows.append((i, "SO%s%07d" % (ct.strftime("%Y%m%d"), i), uid, user_province[uid], status,
                           total, discount, pay, qty_total, random.choice(CHANNELS),
                           pay_time.strftime("%Y-%m-%d %H:%M:%S") if pay_time else None,
                           ct.strftime("%Y-%m-%d %H:%M:%S"), None))
    print("   内存生成完成，用时 %.1fs（订单 %d / 明细 %d）" % (time.time() - started, len(order_rows), len(item_rows)))

    batched(conn, "INSERT INTO t_order (id, order_no, user_id, province_id, order_status, total_amount,"
                  " discount_amount, pay_amount, item_count, channel, pay_time, create_time, remark)"
                  " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", order_rows, label="订单写入")
    batched(conn, "INSERT INTO t_order_item (order_id, order_no, product_id, product_name, category_name,"
                  " unit_price, quantity, item_amount, create_time)"
                  " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)", item_rows, label="明细写入")

    # ---------- 回填明细的订单状态快照 ----------
    print("== 回填明细的订单状态快照（冗余列）==")
    started = time.time()
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE t_order_item i JOIN t_order o ON o.id = i.order_id
            SET i.order_status = o.order_status
        """)
        conn.commit()
    print("   完成（%.1fs）" % (time.time() - started))

    # ---------- 回填统计字段 ----------
    print("== 回填统计字段（用户累计消费 / 商品销量）==")
    started = time.time()
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE t_user u
            LEFT JOIN (
                SELECT user_id, ROUND(SUM(pay_amount), 2) AS amt
                FROM t_order WHERE order_status IN (2,3,4) GROUP BY user_id
            ) t ON t.user_id = u.id
            SET u.total_amount = IFNULL(t.amt, 0)
        """)
        conn.commit()
        print("   用户累计消费回填完成（%.1fs）" % (time.time() - started))
        started = time.time()
        cur.execute("""
            UPDATE t_product p
            LEFT JOIN (
                SELECT i.product_id, SUM(i.quantity) AS qty
                FROM t_order_item i JOIN t_order o ON o.id = i.order_id
                WHERE o.order_status IN (2,3,4) GROUP BY i.product_id
            ) t ON t.product_id = p.id
            SET p.sales_count = IFNULL(t.qty, 0)
        """)
        conn.commit()
        print("   商品销量回填完成（%.1fs）" % (time.time() - started))

    print("== ANALYZE TABLE ==")
    with conn.cursor() as cur:
        for table in ("t_province", "t_product", "t_user", "t_order", "t_order_item"):
            cur.execute("ANALYZE TABLE `%s`" % table)
            cur.fetchall()

    with conn.cursor() as cur:
        cur.execute("""
            SELECT 't_province', COUNT(*) FROM t_province
            UNION ALL SELECT 't_product', COUNT(*) FROM t_product
            UNION ALL SELECT 't_user', COUNT(*) FROM t_user
            UNION ALL SELECT 't_order', COUNT(*) FROM t_order
            UNION ALL SELECT 't_order_item', COUNT(*) FROM t_order_item
        """)
        print("== 最终数据量 ==")
        for name, cnt in cur.fetchall():
            print("   %-14s %d" % (name, cnt))
    conn.close()


if __name__ == "__main__":
    main()
