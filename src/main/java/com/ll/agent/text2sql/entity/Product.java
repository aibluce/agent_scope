package com.ll.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 商品信息表 t_product。 */
@Data
@TableName(value = "t_product", autoResultMap = true)
public class Product implements Serializable {

    /** 商品ID，主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商品编码(SKU)，业务唯一标识，如 P000001。 */
    @TableField("product_no")
    private String productNo;

    /** 商品名称，如 华为 Mate 60 Pro 12+512G。 */
    @TableField("product_name")
    private String productName;

    /** 商品类目：手机数码/家用电器/服饰鞋包/食品生鲜/美妆个护/母婴玩具/图书文娱/运动户外。 */
    @TableField("category_name")
    private String categoryName;

    /** 品牌名称。 */
    @TableField("brand")
    private String brand;

    /** 销售单价（元）。 */
    @TableField("price")
    private BigDecimal price;

    /** 成本价（元）。 */
    @TableField("cost_price")
    private BigDecimal costPrice;

    /** 当前库存数量。 */
    @TableField("stock")
    private Integer stock;

    /** 累计销量（件），冗余统计字段。 */
    @TableField("sales_count")
    private Integer salesCount;

    /** 商品状态：1上架 0下架。 */
    @TableField("status")
    private Integer status;

    /** 上架时间。 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
