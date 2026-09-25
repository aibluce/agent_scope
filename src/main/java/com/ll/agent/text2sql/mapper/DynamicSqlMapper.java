package com.ll.agent.text2sql.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 执行「动态生成的只读 SQL」的 Mapper —— Text2SQL 的核心出口。
 *
 * <p>SQL 由大模型生成后经过 {@code SqlGuard} 安全网关校验（单条语句、只读、表白名单、自动 LIMIT），
 * 再由本 Mapper 通过 MyBatis 执行；结果以 {@code List<Map>} 返回，key 就是 SQL 里的列别名
 * （全局关闭了驼峰转换，保证中文别名和原始列名不被改写）。
 *
 * <p><b>关于 ${sql}：</b>这里必须原样拼接 SQL，无法使用 {@code #{}} 预编译占位符。
 * 因此安全依赖三层防护：① SqlGuard 白名单/关键字校验；② Hikari 只读连接池；
 * ③ 生产环境应使用只读数据库账号。切勿在未校验的情况下直接调用本方法。
 */
@Mapper
public interface DynamicSqlMapper {

    /** 执行一条已通过安全网关校验的 SQL。 */
    @Select("${sql}")
    List<Map<String, Object>> select(@Param("sql") String sql);
}
