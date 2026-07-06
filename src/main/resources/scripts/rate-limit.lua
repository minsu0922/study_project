-- 토큰 버킷(token bucket) 요청 제한 — 로드맵 3. 설계는 docs/09-rate-limiting, 결정 배경은 ADR-0003.
--
-- [왜 Lua 스크립트인가]
-- 요청 제한은 "현재 토큰 수를 읽고 → 계산하고 → 다시 쓰는" 3단계 작업이다.
-- 이걸 자바에서 GET/SET 두 번으로 하면, 두 요청이 동시에 들어왔을 때 둘 다 "토큰 1개 남음"을
-- 읽고 둘 다 통과하는 경쟁 상태(race condition)가 생긴다.
-- Redis는 Lua 스크립트 전체를 다른 명령이 끼어들지 못하게 원자적으로 실행하므로,
-- 읽기-계산-쓰기가 한 덩어리가 되어 이 문제가 사라진다. (분산 락보다 훨씬 싸고 간단)
--
-- [왜 시각을 자바가 아니라 Redis에서 얻나 (TIME 명령)]
-- 앱 서버가 여러 대면 서버마다 시계가 미묘하게 다를 수 있다(clock skew).
-- 버킷의 "마지막 충전 시각"을 서로 다른 시계로 기록하면 토큰이 더 생기거나 덜 생긴다.
-- Redis 서버의 시계 하나만 쓰면 기준이 통일된다. (TIME은 Redis 3.2+의 effects 복제
-- 방식에서 스크립트 안 사용이 허용된다)
--
-- [저장 구조] 키 하나당 해시(hash) {tokens: 남은 토큰(소수 허용), ts: 마지막 계산 시각(ms)}
-- 토큰을 배경 작업으로 주기적으로 채우는 게 아니라, 요청이 왔을 때 "지난번 이후 흐른 시간만큼"
-- 몰아서 채운다(lazy refill). 스케줄러가 필요 없고, 요청이 없는 키는 그냥 만료되어 사라진다.
--
-- KEYS[1] = 버킷 키 (예: rl:auth:1.2.3.4)
-- ARGV[1] = capacity        버킷 최대 토큰 수 (= 순간 버스트 허용량)
-- ARGV[2] = refill_tokens   충전량: refill_period_ms 동안 몇 개 채우나
-- ARGV[3] = refill_period_ms 충전 주기(밀리초)
-- 반환: {허용여부(1/0), 재시도까지 초(ceil), 남은 토큰(내림)}

local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3])

-- Redis 서버 시각 → 밀리초. TIME은 {초, 마이크로초} 배열을 준다.
local time = redis.call('TIME')
local now_ms = time[1] * 1000 + math.floor(time[2] / 1000)

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

-- 키가 없으면 = 처음 온 사용자 = 가득 찬 버킷에서 시작.
if tokens == nil then
    tokens = capacity
    ts = now_ms
end

-- lazy refill: 흐른 시간에 비례해 토큰을 채우되, 최대치(capacity)를 넘지 않는다.
-- (capacity 상한이 없으면 오래 쉬었다 온 사용자가 무한 버스트를 쌓게 된다)
local elapsed_ms = math.max(0, now_ms - ts)
tokens = math.min(capacity, tokens + elapsed_ms * refill_tokens / refill_period_ms)

local allowed = 0
local retry_after_sec = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    -- 토큰 1개가 다시 생길 때까지 걸리는 시간 = 부족분 / 초당 충전 속도.
    -- 클라이언트에게 Retry-After 헤더로 알려 줄 값(올림 — 너무 이르게 재시도하지 않도록).
    local ms_per_token = refill_period_ms / refill_tokens
    retry_after_sec = math.ceil((1 - tokens) * ms_per_token / 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
-- TTL: 빈 버킷이 가득 차는 데 걸리는 시간만큼만 보관. 그보다 오래 안 온 사용자의 버킷은
-- 어차피 "가득 참"과 구별되지 않으므로 지워도 결과가 같다 → 메모리를 공짜로 청소.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_tokens * refill_period_ms))

-- Lua 숫자는 Redis로 돌아갈 때 정수로 잘리므로 소수 토큰은 내림 처리된다(표시용이라 무방).
return {allowed, retry_after_sec, math.floor(tokens)}
