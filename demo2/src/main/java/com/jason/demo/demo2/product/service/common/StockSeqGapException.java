package com.jason.demo.demo2.product.service.common;

/** mysql.stock_seq 还没到 seq-1，消费者应 RECONSUME_LATER，不能当成功。 */
public class StockSeqGapException extends RuntimeException {
    public StockSeqGapException(long productId, long messageSeq, Long currentSeq) {
        super("stock seq gap, productId=" + productId + ", messageSeq=" + messageSeq + ", currentSeq=" + currentSeq);
    }
}
