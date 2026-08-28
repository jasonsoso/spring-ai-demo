package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.vo.res.ProductShelfResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.springframework.stereotype.Service;

/** 上架前 HSETNX 灌闸门；Hash 已在则保持热路径 avail，只改 status。 */
@Service
public class ProductOnShelfCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductStockRepository productStockRepository;
    private final RedisStockOps redisStockOps;

    public ProductOnShelfCmdExe(
            ProductDomainService productDomainService,
            ProductStockRepository productStockRepository,
            RedisStockOps redisStockOps) {
        this.productDomainService = productDomainService;
        this.productStockRepository = productStockRepository;
        this.redisStockOps = redisStockOps;
    }

    public ProductShelfResVO execute(long productId) {
        productDomainService.requireProduct(productId);
        ProductStock stock = productStockRepository.requireByProductId(productId);
        long seq = stock.getStockSeq() == null ? 0L : stock.getStockSeq();
        // 返回值故意忽略：Hash 已存在时禁止 adjustHash/覆盖 avail
        redisStockOps.hsetnxHash(productId, stock.getStock(), seq);
        Product product = productDomainService.onShelf(productId);
        ProductShelfResVO res = new ProductShelfResVO();
        res.setProductId(product.getProductId());
        res.setStatus(product.getStatus());
        return res;
    }
}
