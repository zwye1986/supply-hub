-- 单KEYS独占段消费，TIME与日期守卫先于任何写操作。
-- 设计：docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#43-redis原子自增与回退保护
-- ARGV: 日期零点Unix秒、次日零点Unix秒、段起点、段终点、进程floor、批次数。
local clock = redis.call('TIME')
local seconds = tonumber(clock[1])
local function result(status, sequence)
    return {status, clock[1], clock[2], tostring(sequence)}
end
local function integer(value)
    if not value or not string.match(value, '^%d+$') then return nil end
    if #value > 1 and string.sub(value, 1, 1) == '0' then return nil end
    local parsed = tonumber(value)
    if not parsed or parsed ~= math.floor(parsed) then return nil end
    return parsed
end
if #KEYS ~= 1 or #ARGV ~= 6 then return result('INVALID', 0) end
local dayStart = integer(ARGV[1])
local dayEnd = integer(ARGV[2])
local startValue = integer(ARGV[3])
local endValue = integer(ARGV[4])
local floor = integer(ARGV[5])
local count = integer(ARGV[6])
if not dayStart or not dayEnd or dayStart >= dayEnd or dayEnd - dayStart > 90000 then
    return result('INVALID', 0)
end
if seconds < dayStart or seconds >= dayEnd then return result('DATE_CHANGED', 0) end
if not startValue or not endValue or not floor or not count
        or startValue < 1 or startValue > endValue or endValue > 99999999
        or floor < startValue - 1 or floor > endValue or (count ~= 1 and count ~= 2) then
    return result('INVALID', 0)
end
local raw = redis.call('GET', KEYS[1])
local cursor = floor
if raw then
    cursor = integer(raw)
    if not cursor or cursor < startValue - 1 or cursor > endValue then return result('INVALID', 0) end
end
-- 不降低更大游标；缺键或确认写回退时只从本进程floor向上修复。
if not raw then
    redis.call('SET', KEYS[1], tostring(floor), 'NX')
elseif cursor < floor then
    redis.call('SET', KEYS[1], tostring(floor))
end
cursor = math.max(cursor, floor)
-- TTL是固定清理点，不是号段租约；耗尽路径初始化/修复的键也采用同一绝对过期点。
redis.call('EXPIREAT', KEYS[1], dayEnd + 604800)
if cursor + count > endValue then return result('EXHAUSTED', cursor) end
local allocated = redis.call('INCRBY', KEYS[1], count)
return result('SUCCESS', allocated)
