package com.ll.agent.text2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.agent.text2sql.entity.Province;
import org.apache.ibatis.annotations.Mapper;

/** 省份信息表 Mapper（MyBatis-Plus BaseMapper 已提供单表 CRUD）。 */
@Mapper
public interface ProvinceMapper extends BaseMapper<Province> {}
