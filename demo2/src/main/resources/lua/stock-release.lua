-- ARGV[1]=orderId  ARGV[2]=productId  ARGV[3]=idempotentKey
local stock  = KEYS[1]
local ticket = KEYS[2]
local outbox = KEYS[3]

if redis.call('EXISTS', stock) == 0 then
  return {-1, 'UNLOADED'}
end

local qty = redis.call('GET', ticket)
if not qty then
  return {2, 'NO_TICKET'}                            -- 没票：不是直接当业务成功，Java 见表
end
redis.call('DEL', ticket)                            -- 和 CONFIRM 抢同一张票
redis.call('HINCRBY', stock, 'avail', tonumber(qty)) -- 把可售加回去

local seq = redis.call('HINCRBY', stock, 'seq', 1)
redis.call('XADD', outbox, '*',                      -- MySQL：stock+n, withhold-n
  'productId', ARGV[2], 'orderId', ARGV[1], 'optType', 'RELEASE',
  'qty', qty, 'idempotentKey', ARGV[3], 'seq', seq)
return {1, 'OK'}
