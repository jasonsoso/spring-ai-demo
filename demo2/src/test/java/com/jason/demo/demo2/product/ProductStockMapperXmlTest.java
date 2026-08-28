package com.jason.demo.demo2.product;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductStockMapperXmlTest {

    private static final String NAMESPACE =
            "com.jason.demo.demo2.product.service.infrastructure.dao.mapper.ProductStockMapper";
    private static final String RESOURCE = "mapper/product/ProductStockMapper.xml";

    @Test
    void xmlMapper_registersReserveConfirmRelease() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, () -> "missing classpath resource: " + RESOURCE);
            XMLMapperBuilder builder = new XMLMapperBuilder(in, configuration, RESOURCE, configuration.getSqlFragments());
            builder.parse();
        }

        assertTrue(configuration.hasStatement(NAMESPACE + ".reserve"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".confirm"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".release"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".adjustActual"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".applyReserveDelta"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".applyConfirmDelta"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".applyReleaseDelta"));
    }
}
