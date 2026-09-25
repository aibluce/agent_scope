package com.ll.agent.text2sql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 省份信息表 t_province。
 *
 * <p>{@code autoResultMap = true} 让 MyBatis-Plus 依据实体字段生成 ResultMap，
 * 这样即便全局关闭了驼峰转换（为了保持 Text2SQL 结果的原始列别名），实体映射依然正确。
 */
@Data
@TableName(value = "t_province", autoResultMap = true)
public class Province implements Serializable {

    /** 省份ID，主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 省份行政编码，如 110000。 */
    @TableField("province_code")
    private String provinceCode;

    /** 省份名称，如 广东省。 */
    @TableField("province_name")
    private String provinceName;

    /** 省份简称，如 广东。 */
    @TableField("short_name")
    private String shortName;

    /** 所属大区：华北/华东/华南/华中/西南/西北/东北。 */
    @TableField("region")
    private String region;

    /** 是否启用：1启用 0停用。 */
    @TableField("is_active")
    private Integer isActive;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
