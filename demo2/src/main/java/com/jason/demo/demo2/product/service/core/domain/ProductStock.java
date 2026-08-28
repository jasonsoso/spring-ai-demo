package com.jason.demo.demo2.product.service.core.domain;

import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;

/** 库存聚合。恒等式 stock = actual - withhold；seq 只给投影/对账，不是可售。 */
public class ProductStock extends ProductStockDO {

    public static ProductStock from(ProductStockDO source) {
        if (source == null) {
            return null;
        }
        ProductStock stock = new ProductStock();
        stock.setId(source.getId());
        stock.setStockId(source.getStockId());
        stock.setProductId(source.getProductId());
        stock.setActualStock(source.getActualStock());
        stock.setStock(source.getStock());
        stock.setWithholdStock(source.getWithholdStock());
        stock.setSellStock(source.getSellStock());
        stock.setStockSeq(source.getStockSeq());
        stock.setUpdatedAt(source.getUpdatedAt());
        return stock;
    }

    public ProductStock copy() {
        return ProductStock.from(this);
    }

    public ProductStock applyReserve(int qty) {
        setStock(getStock() - qty);
        setWithholdStock(getWithholdStock() + qty);
        return this;
    }

    public ProductStock applyConfirm(int qty) {
        // 确认不改可售：可售已在 RESERVE 扣过
        setActualStock(getActualStock() - qty);
        setWithholdStock(getWithholdStock() - qty);
        setSellStock(getSellStock() + qty);
        return this;
    }

    public ProductStock applyRelease(int qty) {
        setStock(getStock() + qty);
        setWithholdStock(getWithholdStock() - qty);
        return this;
    }

    public ProductStock applyAdjust(int targetActual) {
        if (targetActual < 0 || targetActual < getWithholdStock()) {
            throw new IllegalArgumentException("targetActual must be >= withhold");
        }
        setActualStock(targetActual);
        setStock(targetActual - getWithholdStock());
        return this;
    }

    /** 从投影后的 after 反推流水 before；applyDelta 无行锁，不能再查一次「更新前」快照。 */
    public static ProductStock reverse(ProductStock after, ProductStockOptTypeEnum op, int n) {
        ProductStock before = after.copy();
        switch (op) {
            case RESERVE -> {
                before.setStock(after.getStock() + n);
                before.setWithholdStock(after.getWithholdStock() - n);
            }
            case CONFIRM -> {
                before.setActualStock(after.getActualStock() + n);
                before.setWithholdStock(after.getWithholdStock() + n);
                before.setSellStock(after.getSellStock() - n);
            }
            case RELEASE -> {
                before.setStock(after.getStock() - n);
                before.setWithholdStock(after.getWithholdStock() + n);
            }
            default -> throw new IllegalArgumentException("cannot reverse " + op);
        }
        return before;
    }

    /** stock = actual - withhold，写流水前必须成立。 */
    public void assertBalance() {
        if (getStock() == null || getActualStock() == null || getWithholdStock() == null) {
            throw new IllegalStateException("stock fields must not be null");
        }
        if (!getStock().equals(getActualStock() - getWithholdStock())) {
            throw new IllegalStateException("stock balance violated: stock="
                    + getStock() + ", actual=" + getActualStock() + ", withhold=" + getWithholdStock());
        }
    }
}
