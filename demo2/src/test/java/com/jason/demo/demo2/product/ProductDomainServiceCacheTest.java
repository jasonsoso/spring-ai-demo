package com.jason.demo.demo2.product;

import com.alicp.jetcache.autoconfigure.JetCacheAutoConfiguration;
import com.jason.demo.demo2.framework.cache.configuration.JetCacheConfiguration;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ProductDomainServiceCacheTest.Slice.class, webEnvironment = WebEnvironment.NONE)
@EnabledIf("redisReachable")
class ProductDomainServiceCacheTest {

    static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Configuration
    @Import({JetCacheConfiguration.class, ProductDomainService.class})
    @ImportAutoConfiguration({
            JetCacheAutoConfiguration.class,
            RedissonAutoConfigurationV4.class,
            DataRedisAutoConfiguration.class
    })
    static class Slice {
    }

    @MockitoBean
    private ProductRepository productRepository;

    @Autowired
    private ProductDomainService productDomainService;

    private static final long PRODUCT_A = 9001L;
    private static final long PRODUCT_B = 9002L;

    @BeforeEach
    void resetRepository() {
        Product product = onShelfProduct(PRODUCT_A);
        when(productRepository.requireByProductId(anyLong())).thenReturn(product);
        when(productRepository.findOnShelfWithStock(PRODUCT_A)).thenReturn(Optional.of(row(PRODUCT_A)));
        when(productRepository.findOnShelfWithStock(PRODUCT_B)).thenReturn(Optional.of(row(PRODUCT_B)));
        when(productRepository.listOnShelfWithStock()).thenReturn(List.of(row(PRODUCT_A)));
        productDomainService.offShelf(PRODUCT_A);
        productDomainService.offShelf(PRODUCT_B);
        clearInvocations(productRepository);
    }

    @Test
    @EnabledIf("redisReachable")
    void listOnShelf_secondCallHitsCache() {
        productDomainService.listOnShelf();
        productDomainService.listOnShelf();
        verify(productRepository, times(1)).listOnShelfWithStock();
    }

    @Test
    @EnabledIf("redisReachable")
    void offShelf_invalidatesListCache() {
        productDomainService.listOnShelf();
        productDomainService.offShelf(PRODUCT_A);
        productDomainService.listOnShelf();
        verify(productRepository, times(2)).listOnShelfWithStock();
    }

    @Test
    @EnabledIf("redisReachable")
    void requireOnShelfWithCache_isPerProductId() {
        productDomainService.requireOnShelfWithCache(PRODUCT_A);
        productDomainService.requireOnShelfWithCache(PRODUCT_A);
        productDomainService.requireOnShelfWithCache(PRODUCT_B);
        verify(productRepository, times(1)).findOnShelfWithStock(PRODUCT_A);
        verify(productRepository, times(1)).findOnShelfWithStock(PRODUCT_B);
    }

    @Test
    @EnabledIf("redisReachable")
    void requireOnShelf_doesNotCache() {
        productDomainService.requireOnShelf(PRODUCT_A);
        productDomainService.requireOnShelf(PRODUCT_A);
        verify(productRepository, times(2)).findOnShelfWithStock(PRODUCT_A);
    }

    @Test
    @EnabledIf("redisReachable")
    void contextLoads_whenRedisUp() {
        assertNotNull(productDomainService);
    }

    private static ProductWithStock row(long productId) {
        return new ProductWithStock(onShelfProduct(productId), stock(productId));
    }

    private static Product onShelfProduct(long productId) {
        Product product = new Product();
        product.setProductId(productId);
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        product.setProductName("p-" + productId);
        return product;
    }

    private static ProductStock stock(long productId) {
        ProductStock stock = new ProductStock();
        stock.setProductId(productId);
        stock.setStock(10);
        stock.setSellStock(1);
        stock.setActualStock(10);
        stock.setWithholdStock(0);
        return stock;
    }
}
