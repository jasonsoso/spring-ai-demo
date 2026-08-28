-- KEYS[1]=库存 Hash
-- ARGV[1]=新可售（Java 算好：v - withhold）
-- ARGV[2]=新 seq（= MySQL 行锁更新后的 stock_seq）
redis.call('HSET', KEYS[1],
  'avail', ARGV[1],                                  -- 覆盖可售，不是 ±n
  'seq', ARGV[2])                                    -- 与库对齐，后面热路径从这里继续 +1
return {1, 'OK'}
