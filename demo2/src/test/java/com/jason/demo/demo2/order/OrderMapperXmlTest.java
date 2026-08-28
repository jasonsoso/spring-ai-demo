package com.jason.demo.demo2.order;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMapperXmlTest {

    private static final String NAMESPACE =
            "com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderMapper";
    private static final String RESOURCE = "mapper/order/OrderMapper.xml";

    @Test
    void xmlMapper_registersCasAndPageStatements() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, () -> "missing classpath resource: " + RESOURCE);
            new XMLMapperBuilder(in, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        assertTrue(configuration.hasStatement(NAMESPACE + ".markCompleted"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".markCancelled"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".countByMemberAndStatus"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".countPageByMemberAndTab"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".pageByMemberAndTab"));
        MappedStatement count = configuration.getMappedStatement(NAMESPACE + ".countByMemberAndStatus");
        String sql = count.getBoundSql(Map.of("memberId", 1L, "orderStatus", "SUBMIT")).getSql()
                .replaceAll("\\s+", " ");
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM demo_order_item i WHERE i.order_id = o.order_id)"));
    }
}
