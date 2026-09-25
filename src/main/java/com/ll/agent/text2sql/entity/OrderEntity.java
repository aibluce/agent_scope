package com.ll.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 订单表 t_order（订单主表）。 */
@Data
@TableName(value = "t_order", autoResultMap = true)
public class OrderEntity implements Serializable {

    /** 订单ID，主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单编号，业务唯一标识，如 SO202406120001。 */
    @TableField("order_no")
    private String orderNo;

    /** 下单用户ID，关联 t_user.id。 */
    @TableField("user_id")
    private Long userId;

    /** 收货省份ID，关联 t_province.id。 */
    @TableField("province_id")
    private Long provinceId;

    /** 订单状态：1待付款 2已付款 3已发货 4已完成 5已取消 6已退款。 */
    @TableField("order_status")
    private Integer orderStatus;

    /** 订单总金额（优惠前，元）。 */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 优惠金额（元）。 */
    @TableField("discount_amount")
    private BigDecimal discountAmount;

    /** 实付金额（元）。 */
    @TableField("pay_amount")
    private BigDecimal payAmount;

    /** 商品件数。 */
    @TableField("item_count")
    private Integer itemCount;

    /** 下单渠道：APP/小程序/PC/H5。 */
    @TableField("channel")
    private String channel;

    /** 支付时间，未支付为 NULL。 */
    @TableField("pay_time")
    private LocalDateTime payTime;

    /** 下单时间。 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 订单备注。 */
    @TableField("remark")
    private String remark;
}
