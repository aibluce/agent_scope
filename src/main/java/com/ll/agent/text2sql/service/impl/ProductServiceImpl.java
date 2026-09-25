package com.ll.agent.text2sql.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ll.agent.text2sql.entity.Product;
import com.ll.agent.text2sql.mapper.ProductMapper;
import com.ll.agent.text2sql.service.ProductService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 商品服务实现：MyBatis-Plus 的 {@link ServiceImpl} + {@link QueryWrapper}。 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    @Override
    public Page<Product> pageQuery(long current, long size, String category, String keyword) {
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.eq(StringUtils.hasText(category), "category_name", category)
                .like(StringUtils.hasText(keyword), "product_name", keyword)
                .orderByDesc("sales_count");
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public List<Map<String, Object>> categoryStats() {
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.select(
                        "category_name AS categoryName",
                        "COUNT(*) AS productCount",
                        "SUM(sales_count) AS salesCount",
                        "ROUND(AVG(price), 2) AS avgPrice",
                        "MAX(price) AS maxPrice")
                .groupBy("category_name")
                .orderByDesc("salesCount");
        return baseMapper.selectMaps(wrapper);
    }
}
