package com.jason.demo.demo2.order.service.infrastructure.shard;

/**
 * 订单分片基因与路由纯函数。常量与公式写死，禁止做成配置项，否则已发出的订单号会对不上。
 *
 * <p>基因是 64 位订单号最低 9 bit（{@code 0x1FF}）。发号见 {@link OrderIdGenerator}（时间|机器|序号|基因），
 * 本类只负责 {@code virtual} / 库表下标，不负责拼号。
 *
 * <p>禁止写成 {@code table = virtual % 32}：2 与 32 不互质，会出现「一库只落偶数表、另一库只落奇数表」。
 */
public final class OrderShardGene {

    /** 基因位数；天花板 2×256 = 512 = 2^9。 */
    public static final int GENE_BITS = 9;
    /** 虚拟分片数，等于 2^GENE_BITS。会员和订单号都先落到这 512 个槽，再拆库表。 */
    public static final int VIRTUAL_COUNT = 512;
    /** 物理库数。库下标 = virtual % DB_COUNT。 */
    public static final int DB_COUNT = 2;
    /** 现在每库表数；扩到 256 只需改这里并搬行，不用改订单号。 */
    public static final int TABLE_COUNT = 32;
    /** 低 9 位掩码，与 GENE_BITS 对齐。 */
    public static final long GENE_MASK = 0x1FFL;

    private OrderShardGene() {
    }

    /** 下单时写入订单号低 9 位的值。同一会员永远同一个 virtual，所以总进同一库表。 */
    public static long virtualOfMember(long memberId) {
        return memberId % VIRTUAL_COUNT;
    }

    /** 只拿订单号时拆低 9 位，供超时关单 / selectById 直达库表。 */
    public static long virtualOfOrderId(long orderId) {
        return orderId & GENE_MASK;
    }

    /** 偶数 virtual 进 order_ds_0，奇数进 order_ds_1。 */
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

    /** 调试展示用，把 virtual 补成 9 位二进制，高位补 0。不参与路由。 */
    public static String geneBits(long virtual) {
        String bits = Long.toBinaryString(virtual & GENE_MASK);
        return "0".repeat(GENE_BITS - bits.length()) + bits;
    }

    /** 名字必须和 shardingsphere.yaml 的 actualDataNodes 一致。 */
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
