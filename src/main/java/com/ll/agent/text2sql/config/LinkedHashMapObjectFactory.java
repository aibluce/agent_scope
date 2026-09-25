package com.ll.agent.text2sql.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;

/**
 * 让 MyBatis 用 {@link LinkedHashMap} 承载 {@code resultType=Map} 的结果。
 *
 * <p>MyBatis 默认用 HashMap，列的顺序会被打乱；Text2SQL 的结果列顺序就是模型写出的 SQL
 * 里的列顺序，返回给前端时要保持稳定，所以这里换成 LinkedHashMap。
 */
public class LinkedHashMapObjectFactory extends DefaultObjectFactory {

    @Override
    public <T> T create(Class<T> type) {
        if (Map.class.isAssignableFrom(type)) {
            return type.cast(new LinkedHashMap<String, Object>());
        }
        return super.create(type);
    }

    @Override
    public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        if (Map.class.isAssignableFrom(type)) {
            return type.cast(new LinkedHashMap<String, Object>());
        }
        return super.create(type, constructorArgTypes, constructorArgs);
    }
}
