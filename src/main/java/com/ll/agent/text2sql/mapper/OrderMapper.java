package com.ll.agent.text2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.agent.text2sql.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

/** 订单表 Mapper。 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {}
