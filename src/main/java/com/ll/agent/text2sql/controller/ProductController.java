package com.ll.agent.text2sql.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ll.agent.text2sql.entity.Product;
import com.ll.agent.text2sql.service.ProductService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口：演示 MyBatis-Plus 的标准用法（BaseMapper / IService / 分页插件 / Wrapper）。
 *
 * <p>与 Text2SQL 接口的区别：这里的 SQL 是确定的、写在代码里的；
 * Text2SQL 接口的 SQL 是运行时由大模型生成的。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** 分页查询：GET /api/products?current=1&size=5&category=手机数码&keyword=小米 */
    @GetMapping
    public Page<Product> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return productService.pageQuery(current, size, category, keyword);
    }

    /** 主键查询：MyBatis-Plus BaseMapper.selectById。 */
    @GetMapping("/{id}")
    public Product detail(@PathVariable Long id) {
        return productService.getById(id);
    }

    /** 类目聚合统计：QueryWrapper 的 select + groupBy。 */
    @GetMapping("/stats/category")
    public List<Map<String, Object>> categoryStats() {
        return productService.categoryStats();
    }
}
