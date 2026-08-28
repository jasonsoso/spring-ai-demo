package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.app.vo.res.AdjustStockResVO;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.redis.RedisStockOps;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 运营改现货：必须下架且 Redis seq 与 MySQL 对齐后再行锁 ADJUST，最后覆盖 Hash。 */
@Service
public class ProductAdjustStockCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductStockDomainService productStockDomainService;
    private final ProductStockRepository productStockRepository;
    private final RedisStockOps redisStockOps;
    private final SnowflakeIdGenerator idGenerator;

    public ProductAdjustStockCmdExe(
            ProductDomainService productDomainService,
            ProductStockDomainService productStockDomainService,
            ProductStockRepository productStockRepository,
            RedisStockOps redisStockOps,
            SnowflakeIdGenerator idGenerator) {
        this.productDomainService = productDomainService;
        this.productStockDomainService = productStockDomainService;
        this.productStockRepository = productStockRepository;
        this.redisStockOps = redisStockOps;
        this.idGenerator = idGenerator;
    }

    public AdjustStockResVO execute(long productId, int targetActual) {
        Product product = productDomainService.requireProduct(productId);
        if (!ProductStatusEnum.OFF_SHELF.name().equals(product.getStatus())) {
            throw new BusinessException(ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF);
        }
        ProductStock mysql = productStockRepository.requireByProductId(productId);
        Optional<Long> redisSeq = redisStockOps.getSeq(productId);
        if (redisSeq.isPresent()) {
            long mysqlSeq = mysql.getStockSeq() == null ? 0L : mysql.getStockSeq();
            if (!redisSeq.get().equals(mysqlSeq)) {
                // Hash 存在但投影未齐：再 ADJUST 会把 seq 与热路径打乱
                throw new BusinessException(ProductErrorCodeEnum.STOCK_SYNC_LAG);
            }
        }
        ProductStock after = productStockDomainService.adjust(productId, targetActual, idGenerator.nextId());
        redisStockOps.adjustHash(productId, after.getStock(), after.getStockSeq());
        AdjustStockResVO res = new AdjustStockResVO();
        res.setProductId(after.getProductId());
        res.setActualStock(after.getActualStock());
        res.setStock(after.getStock());
        res.setWithholdStock(after.getWithholdStock());
        res.setStockSeq(after.getStockSeq());
        return res;
    }
}
