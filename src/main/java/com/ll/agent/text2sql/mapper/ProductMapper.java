package com.ll.agent.text2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.agent.text2sql.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/** 商品信息表 Mapper。 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {}
