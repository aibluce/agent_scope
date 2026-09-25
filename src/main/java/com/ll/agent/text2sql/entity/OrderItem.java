package com.ll.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 订单明细表 t_order_item（订单与商品的多对多关联）。 */
@Data
@TableName(value = "t_order_item", autoResultMap = true)
public class OrderItem implements Serializable {

    /** 明细ID，主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单ID，关联 t_order.id。 */
    @TableField("order_id")
    private Long orderId;

    /** 订单编号，冗余自 t_order.order_no。 */
    @TableField("order_no")
    private String orderNo;

    /** 商品ID，关联 t_product.id。 */
    @TableField("product_id")
    private Long productId;

    /** 商品名称快照（下单时的名称）。 */
    @TableField("product_name")
    private String productName;

    /** 商品类目快照，冗余自 t_product.category_name。 */
    @TableField("category_name")
    private String categoryName;

    /** 成交单价（元）。 */
    @TableField("unit_price")
    private BigDecimal unitPrice;

    /** 购买数量（件）。 */
    @TableField("quantity")
    private Integer quantity;

    /** 明细金额（元）= 成交单价 × 购买数量。 */
    @TableField("item_amount")
    private BigDecimal itemAmount;

    /** 下单时间，与 t_order.create_time 一致。 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
