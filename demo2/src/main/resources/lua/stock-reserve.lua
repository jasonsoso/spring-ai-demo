-- 热路径预占：只动 avail+seq+票，actual/withhold/sell 留给 MySQL 投影。
-- ARGV[1]=qty  ARGV[2]=orderId  ARGV[3]=productId  ARGV[4]=idempotentKey
local stock  = KEYS[1]                               -- 这件商品的可售 + seq
local ticket = KEYS[2]                               -- 这一单有没有预占过
local outbox = KEYS[3]                               -- 稍后同步 MySQL
local qty    = tonumber(ARGV[1])                     -- 要预占几件

if redis.call('EXISTS', stock) == 0 then             -- 还没从 MySQL 灌过（从未上架成功）
  return {-1, 'UNLOADED'}                            -- 热卖中不要用库里的 stock 去灌
end

-- SETNX：没有票才写入 qty；已有票说明这单已经预占过
if redis.call('SETNX', ticket, qty) == 0 then
  if tonumber(redis.call('GET', ticket)) == qty then
    return {2, 'IDEMPOTENT'}                         -- 同样数量，重复提交，不扣第二次
  end
  return {0, 'CONFLICT'}                             -- 已有票但数量不同
end

local left = redis.call('HINCRBY', stock, 'avail', -qty) -- 只减可售
if left < 0 then                                     -- 减成负数 = 超卖
  redis.call('HINCRBY', stock, 'avail', qty)         -- 加回去
  redis.call('DEL', ticket)                          -- 撕掉刚写的票
  return {0, 'INSUFFICIENT'}
end

local seq = redis.call('HINCRBY', stock, 'seq', 1)   -- 序号+1，给 MySQL 排队/对账，不是库存
redis.call('XADD', outbox, '*',                      -- 告诉 MySQL：stock-n, withhold+n
  'productId', ARGV[3], 'orderId', ARGV[2], 'optType', 'RESERVE',
  'qty', qty, 'idempotentKey', ARGV[4], 'seq', seq)
return {1, 'OK'}                                     -- 调用方立刻成功
