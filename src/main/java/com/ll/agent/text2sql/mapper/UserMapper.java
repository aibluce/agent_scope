package com.ll.agent.text2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ll.agent.text2sql.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** 用户信息表 Mapper。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {}
