package com.ll.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 用户信息表 t_user。 */
@Data
@TableName(value = "t_user", autoResultMap = true)
public class User implements Serializable {

    /** 用户ID，主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户编号，业务唯一标识，如 U20240001。 */
    @TableField("user_no")
    private String userNo;

    /** 登录用户名。 */
    @TableField("username")
    private String username;

    /** 真实姓名。 */
    @TableField("real_name")
    private String realName;

    /** 手机号。 */
    @TableField("phone")
    private String phone;

    /** 邮箱。 */
    @TableField("email")
    private String email;

    /** 性别：0未知 1男 2女。 */
    @TableField("gender")
    private Integer gender;

    /** 年龄。 */
    @TableField("age")
    private Integer age;

    /** 会员等级：1普通会员 2银卡会员 3金卡会员 4钻石会员。 */
    @TableField("member_level")
    private Integer memberLevel;

    /** 常住省份ID，关联 t_province.id。 */
    @TableField("province_id")
    private Long provinceId;

    /** 常住城市。 */
    @TableField("city")
    private String city;

    /** 累计消费金额（元）。 */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 账号状态：1正常 0已禁用。 */
    @TableField("status")
    private Integer status;

    /** 注册时间。 */
    @TableField("register_time")
    private LocalDateTime registerTime;
}
