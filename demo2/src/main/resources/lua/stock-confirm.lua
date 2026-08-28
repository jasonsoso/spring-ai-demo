-- ARGV[1]=orderId  ARGV[2]=productId  ARGV[3]=idempotentKey
local stock  = KEYS[1]                               -- 还要给 seq+1，所以要有这个 Hash
local ticket = KEYS[2]
local outbox = KEYS[3]

if redis.call('EXISTS', stock) == 0 then
  return {-1, 'UNLOADED'}
end

local qty = redis.call('GET', ticket)                -- 读取预占数量
if not qty then
  return {0, 'NOT_FOUND'}                            -- 没票：可能已支付、已取消，或消息还在路上（Java 见表）
end
redis.call('DEL', ticket)                            -- 删票；和 RELEASE 谁先 DEL 谁赢
-- 注意：这里不改 avail。可售在 RESERVE 时已经扣过了

local seq = redis.call('HINCRBY', stock, 'seq', 1)   -- 仍要 +1，否则对账会认为少记了一笔
redis.call('XADD', outbox, '*',                      -- MySQL：actual-n, withhold-n, sell+n
  'productId', ARGV[2], 'orderId', ARGV[1], 'optType', 'CONFIRM',
  'qty', qty, 'idempotentKey', ARGV[3], 'seq', seq)
return {1, 'OK'}
