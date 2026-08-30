package com.jason.demo.demo2.order.service.infrastructure.shard;

public final class OrderShardGene {

    public static final int GENE_BITS = 9;
    public static final int VIRTUAL_COUNT = 512;
    public static final int DB_COUNT = 2;
    public static final int TABLE_COUNT = 32;
    public static final long GENE_MASK = 0x1FFL;

    private OrderShardGene() {
    }

    public static long virtualOfMember(long memberId) {
        return memberId % VIRTUAL_COUNT;
    }

    public static long virtualOfOrderId(long orderId) {
        return orderId & GENE_MASK;
    }

    public static int dsIndex(long virtual) {
        return (int) (virtual % DB_COUNT);
    }

    public static int tableIndex(long virtual) {
        return (int) ((virtual / DB_COUNT) % TABLE_COUNT);
    }

    public static long embed(long raw, long memberId) {
        return (raw & ~GENE_MASK) | virtualOfMember(memberId);
    }

    public static String geneBits(long virtual) {
        String bits = Long.toBinaryString(virtual & GENE_MASK);
        return "0".repeat(GENE_BITS - bits.length()) + bits;
    }

    public static String dsName(long virtual) {
        return "order_ds_" + dsIndex(virtual);
    }

    public static String orderTableName(long virtual) {
        return "demo_order_" + tableIndex(virtual);
    }

    public static String itemTableName(long virtual) {
        return "demo_order_item_" + tableIndex(virtual);
    }
}
