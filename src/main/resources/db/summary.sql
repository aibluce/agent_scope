-- ============================================================================
-- 预聚合汇总表（可选，用于性能优化演示）
--
-- 动机：10 万订单 / 25 万明细下，"按类目/按月汇总销售额"这类查询即使走了覆盖索引，
--       也要扫 20 多万行做分组，稳定在 100~300ms 量级。要再快只能"空间换时间"：
--       把日×类目粒度的结果预先算好（定时任务/Nightly 刷新），查询退化成扫几千行的小表。
--
-- 注意：本表属于"衍生数据"，默认不参与 Text2SQL 的白名单。
--       若要让它参与问答，记得把它加进 t2sql.sql.allowed-tables，
--       并在注释里写清楚口径（模型靠注释理解字段语义）。
-- ============================================================================

USE `ecommerce`;

CREATE TABLE IF NOT EXISTS `t_sales_summary_daily` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`     DATE          NOT NULL                COMMENT '统计日期',
    `category_name` VARCHAR(32)   NOT NULL                COMMENT '商品类目',
    `order_count`   INT           NOT NULL DEFAULT 0      COMMENT '成交订单数（去重）',
    `item_quantity` INT           NOT NULL DEFAULT 0      COMMENT '成交商品件数',
    `sales_amount`  DECIMAL(14,2) NOT NULL DEFAULT 0.00   COMMENT '销售额（明细金额合计）',
    `cost_amount`   DECIMAL(14,2) NOT NULL DEFAULT 0.00   COMMENT '成本（数量 × 商品成本价）',
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '刷新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_category` (`stat_date`, `category_name`),
    KEY `idx_category` (`category_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '类目日销售汇总表（预聚合）';

-- 全量刷新（生产上一般按天增量刷新，这里演示用 REPLACE 全量重建）
REPLACE INTO `t_sales_summary_daily`
    (`stat_date`, `category_name`, `order_count`, `item_quantity`, `sales_amount`, `cost_amount`)
SELECT DATE(`i`.`create_time`)              AS `stat_date`,
       `i`.`category_name`,
       COUNT(DISTINCT `i`.`order_id`)        AS `order_count`,
       SUM(`i`.`quantity`)                   AS `item_quantity`,
       ROUND(SUM(`i`.`item_amount`), 2)      AS `sales_amount`,
       ROUND(SUM(`i`.`quantity` * `p`.`cost_price`), 2) AS `cost_amount`
FROM `t_order_item` `i`
JOIN `t_product` `p` ON `p`.`id` = `i`.`product_id`
WHERE `i`.`order_status` IN (2, 3, 4)
GROUP BY DATE(`i`.`create_time`), `i`.`category_name`;
