package com.ll.agent.text2sql.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ll.agent.text2sql.entity.Product;
import java.util.List;
import java.util.Map;

/**
 * 商品服务：演示 MyBatis-Plus 的 IService / 分页 / Wrapper 聚合查询。
 *
 * <p>注意：MyBatis-Plus 3.5.17 把 IService / ServiceImpl 迁到了
 * {@code com.baomidou.mybatisplus.spring.service}（mybatis-plus-spring 模块）。
 */
public interface ProductService extends IService<Product> {

    /** 条件分页查询（MyBatis-Plus 分页插件）。 */
    Page<Product> pageQuery(long current, long size, String category, String keyword);

    /** 类目维度的聚合统计（QueryWrapper + selectMaps）。 */
    List<Map<String, Object>> categoryStats();
}
