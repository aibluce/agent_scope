package com.ll.agent.text2sql.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 插件配置。 */
@Configuration
public class MybatisPlusConfig {

    /** 分页插件：/api/products 这类列表接口直接返回 MyBatis-Plus 的 Page 对象。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，防止调用方传个巨大的 size 把库拖垮
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /** 保留结果列顺序（Text2SQL 动态查询结果以 Map 承载）。 */
    @Bean
    public ConfigurationCustomizer linkedHashMapObjectFactoryCustomizer() {
        return configuration -> configuration.setObjectFactory(new LinkedHashMapObjectFactory());
    }
}
