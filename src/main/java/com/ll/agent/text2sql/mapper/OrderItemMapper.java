package com.ll.agent.text2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.agent.text2sql.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

/** 订单明细表 Mapper。 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {}
