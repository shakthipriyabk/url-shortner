-- Token bucket, executed atomically inside Redis.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity          (max burst)
-- ARGV[2]  refill per second (sustained rate)
-- ARGV[3]  tokens requested  (normally 1)
--
-- Returns { allowed (1|0), tokens remaining (floored), retry-after seconds }
--
-- The clock comes from Redis (TIME), not from the app servers, so skew between
-- instances can't hand out extra tokens. Running as a script makes
-- read -> refill -> decide -> write a single atomic step, so concurrent
-- requests from many app instances can't race.

local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
local retry_after = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry_after = math.ceil((requested - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Idle buckets expire after twice the time needed to refill fully, so Redis doesn't fill up with dead keys.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) * 2)

return { allowed, math.floor(tokens), retry_after }
