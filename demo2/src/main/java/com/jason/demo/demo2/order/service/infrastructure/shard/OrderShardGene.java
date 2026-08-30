package com.jason.demo.demo2.order.service.infrastructure.shard;

/**
 * 订单分片基因纯函数。常量与公式写死，禁止做成配置项，否则已发出的订单号会对不上。
 *
 * <p>基因是 64 位订单号最低 9 bit（{@code 0x1FF}），不是十进制位数。虚拟分片 512 等于天花板 2 库 × 256 表。
 * 现在拓扑 2×32：{@code ds = virtual % 2}，{@code table = (virtual / 2) % 32}。
 *
 * <p>禁止写成 {@code table = virtual % 32}：2 与 32 不互质，会出现「一库只落偶数表、另一库只落奇数表」。
 */
public final class OrderShardGene {

    /** 基因位数；天花板 2×256 = 512 = 2^9。 */
    public static final int GENE_BITS = 9;
    public static final int VIRTUAL_COUNT = 512;
    public static final int DB_COUNT = 2;
    /** 现在每库表数；扩到 256 只需改这里并搬行，不用改订单号。 */
    public static final int TABLE_COUNT = 32;
    public static final long GENE_MASK = 0x1FFL;

    private OrderShardGene() {
    }

    public static long virtualOfMember(long memberId) {
        return memberId % VIRTUAL_COUNT;
    }

    /** 只拿订单号时拆低 9 位，供超时关单 / selectById 直达库表。 */
    public static long virtualOfOrderId(long orderId) {
        return orderId & GENE_MASK;
    }

    public static int dsIndex(long virtual) {
        return (int) (virtual % DB_COUNT);
    }

    /**
     * 先整除库数再对表数取模，保证两库都用满 0..31。
     * 不要改成 {@code virtual % TABLE_COUNT}。
     */
    public static int tableIndex(long virtual) {
        return (int) ((virtual / DB_COUNT) % TABLE_COUNT);
    }

    /** 覆盖雪花低 9 位为 {@code memberId % 512}，高位时间戳/机器位保持不变。 */
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
