-- ============================================================================
-- Text2SQL Demo —— 电商场景示例库（MySQL 8.0）
-- 三张表：省份信息表 t_province / 用户信息表 t_user / 订单表 t_order
-- 所有表和字段都带 COMMENT，Text2SQL 智能体会读取 information_schema 中的
-- 注释来理解业务语义，因此注释质量直接决定生成 SQL 的准确率。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `ecommerce`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `ecommerce`;

DROP TABLE IF EXISTS `t_order_item`;
DROP TABLE IF EXISTS `t_order`;
DROP TABLE IF EXISTS `t_user`;
DROP TABLE IF EXISTS `t_product`;
DROP TABLE IF EXISTS `t_province`;

-- ---------------------------------------------------------------------------
-- 省份信息表
-- ---------------------------------------------------------------------------
CREATE TABLE `t_province` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '省份ID，主键',
    `province_code` VARCHAR(12)  NOT NULL                COMMENT '省份行政编码，如 110000',
    `province_name` VARCHAR(32)  NOT NULL                COMMENT '省份名称，如 广东省',
    `short_name`    VARCHAR(16)  DEFAULT NULL            COMMENT '省份简称，如 广东',
    `region`        VARCHAR(16)  NOT NULL                COMMENT '所属大区：华北/华东/华南/华中/西南/西北/东北',
    `is_active`     TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用：1启用 0停用',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_province_code` (`province_code`),
    KEY `idx_region` (`region`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '省份信息表';

-- ---------------------------------------------------------------------------
-- 用户信息表
-- ---------------------------------------------------------------------------
CREATE TABLE `t_user` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
    `user_no`       VARCHAR(32)   NOT NULL                COMMENT '用户编号，业务唯一标识，如 U20240001',
    `username`      VARCHAR(64)   NOT NULL                COMMENT '登录用户名',
    `real_name`     VARCHAR(64)   DEFAULT NULL            COMMENT '真实姓名',
    `phone`         VARCHAR(20)   DEFAULT NULL            COMMENT '手机号',
    `email`         VARCHAR(128)  DEFAULT NULL            COMMENT '邮箱',
    `gender`        TINYINT       NOT NULL DEFAULT 0      COMMENT '性别：0未知 1男 2女',
    `age`           INT           DEFAULT NULL            COMMENT '年龄',
    `member_level`  TINYINT       NOT NULL DEFAULT 1      COMMENT '会员等级：1普通会员 2银卡会员 3金卡会员 4钻石会员',
    `province_id`   BIGINT        DEFAULT NULL            COMMENT '常住省份ID，关联 t_province.id',
    `city`          VARCHAR(64)   DEFAULT NULL            COMMENT '常住城市',
    `total_amount`  DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '累计消费金额（元）',
    `status`        TINYINT       NOT NULL DEFAULT 1      COMMENT '账号状态：1正常 0已禁用',
    `register_time` DATETIME      NOT NULL                COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_no` (`user_no`),
    KEY `idx_province_id` (`province_id`),
    KEY `idx_register_time` (`register_time`),
    KEY `idx_member_level` (`member_level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户信息表';

-- ---------------------------------------------------------------------------
-- 订单表
-- ---------------------------------------------------------------------------
CREATE TABLE `t_order` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID，主键',
    `order_no`        VARCHAR(32)   NOT NULL                COMMENT '订单编号，业务唯一标识，如 SO202406120001',
    `user_id`         BIGINT        NOT NULL                COMMENT '下单用户ID，关联 t_user.id',
    `province_id`     BIGINT        NOT NULL                COMMENT '收货省份ID，关联 t_province.id',
    `order_status`    TINYINT       NOT NULL DEFAULT 1      COMMENT '订单状态：1待付款 2已付款 3已发货 4已完成 5已取消 6已退款',
    `total_amount`    DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '订单总金额（优惠前，元）',
    `discount_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '优惠金额（元）',
    `pay_amount`      DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '实付金额（元）= 订单总金额 - 优惠金额',
    `item_count`      INT           NOT NULL DEFAULT 0      COMMENT '商品件数',
    `channel`         VARCHAR(16)   NOT NULL DEFAULT 'APP'  COMMENT '下单渠道：APP/小程序/PC/H5',
    `pay_time`        DATETIME      DEFAULT NULL            COMMENT '支付时间，未支付为 NULL',
    `create_time`     DATETIME      NOT NULL                COMMENT '下单时间',
    `remark`          VARCHAR(255)  DEFAULT NULL            COMMENT '订单备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_province_id` (`province_id`),
    KEY `idx_create_time` (`create_time`),
    -- 覆盖索引：① 按订单状态过滤（成交口径 order_status IN (2,3,4)）
    --           ② 直接按省份聚合实付金额，无需回表
    -- （order_status 是最左前缀，因此不再单独建 idx_status）
    KEY `idx_status_province_amount` (`order_status`, `province_id`, `pay_amount`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ---------------------------------------------------------------------------
-- 商品信息表
-- ---------------------------------------------------------------------------
CREATE TABLE `t_product` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID，主键',
    `product_no`    VARCHAR(32)   NOT NULL                COMMENT '商品编码(SKU)，业务唯一标识，如 P000001',
    `product_name`  VARCHAR(128)  NOT NULL                COMMENT '商品名称，如 华为 Mate 60 Pro 12+512G',
    `category_name` VARCHAR(32)   NOT NULL                COMMENT '商品类目：手机数码/家用电器/服饰鞋包/食品生鲜/美妆个护/母婴玩具/图书文娱/运动户外',
    `brand`         VARCHAR(64)   DEFAULT NULL            COMMENT '品牌名称，如 华为/小米/苹果',
    `price`         DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '销售单价（元），商品当前标价',
    `cost_price`    DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '成本价（元），用于计算毛利',
    `stock`         INT           NOT NULL DEFAULT 0      COMMENT '当前库存数量',
    `sales_count`   INT           NOT NULL DEFAULT 0      COMMENT '累计销量（件），冗余统计字段，口径为已付款/已发货/已完成订单的购买数量之和',
    `status`        TINYINT       NOT NULL DEFAULT 1      COMMENT '商品状态：1上架 0下架',
    `create_time`   DATETIME      NOT NULL                COMMENT '上架时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_no` (`product_no`),
    KEY `idx_category_name` (`category_name`),
    KEY `idx_brand` (`brand`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品信息表';

-- ---------------------------------------------------------------------------
-- 订单明细表（订单与商品的多对多关联，一笔订单可包含多个商品）
-- ---------------------------------------------------------------------------
CREATE TABLE `t_order_item` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细ID，主键',
    `order_id`        BIGINT        NOT NULL                COMMENT '订单ID，关联 t_order.id',
    `order_no`        VARCHAR(32)   NOT NULL                COMMENT '订单编号，冗余自 t_order.order_no',
    `product_id`      BIGINT        NOT NULL                COMMENT '商品ID，关联 t_product.id',
    `product_name`    VARCHAR(128)  NOT NULL                COMMENT '商品名称快照（下单时的名称，可能与 t_product.product_name 不一致）',
    `category_name`   VARCHAR(32)   NOT NULL                COMMENT '商品类目快照，冗余自 t_product.category_name',
    `order_status`    TINYINT       NOT NULL DEFAULT 1      COMMENT '订单状态快照，冗余自 t_order.order_status（明细可直接按成交口径过滤，避免回表关联订单表）',
    `unit_price`      DECIMAL(12,2) NOT NULL                COMMENT '成交单价（元），下单时的实际售价，可能低于 t_product.price',
    `quantity`        INT           NOT NULL DEFAULT 1      COMMENT '购买数量（件）',
    `item_amount`     DECIMAL(12,2) NOT NULL DEFAULT 0.00   COMMENT '明细金额（元）= 成交单价 × 购买数量',
    `create_time`     DATETIME      NOT NULL                COMMENT '下单时间，与 t_order.create_time 一致',
    PRIMARY KEY (`id`),
    -- 覆盖索引：按订单取明细（order_id 是最左前缀，因此不再单独建 idx_order_id）
    KEY `idx_order_cover` (`order_id`, `product_id`, `quantity`, `item_amount`),
    -- 覆盖索引：成交口径下的类目/商品汇总（order_status 前缀 + 聚合列都在索引里，无需回表、无需关联订单表）
    KEY `idx_status_category` (`order_status`, `category_name`, `quantity`, `item_amount`),
    KEY `idx_status_product` (`order_status`, `product_id`, `quantity`, `item_amount`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表';
