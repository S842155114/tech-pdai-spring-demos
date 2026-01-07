package tech.pdai.springboot.redis.lettuce.lock.lock;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author pdai
 */
@Slf4j
public class RedisDistributedLock {

    /**
     * lua script for unlock.
     */
    private static final String UNLOCK_LUA;

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("if redis.call(\"get\",KEYS[1]) == ARGV[1] ");
        sb.append("then ");
        sb.append("    return redis.call(\"del\",KEYS[1]) ");
        sb.append("else ");
        sb.append("    return 0 ");
        sb.append("end ");
        UNLOCK_LUA = sb.toString();
    }

    /**
     * unique lock flag based on thread local.
     */
    private final ThreadLocal<String> lockFlag = new ThreadLocal<>();

    private final StringRedisTemplate redisTemplate;

    /**
     * Pre-compiled RedisScript for Lettuce execution.
     */
    private final RedisScript<Long> unlockScript;

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        // Initialize the script once to improve performance
        this.unlockScript = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
    }

    public boolean lock(String key, long expire, int retryTimes, long retryDuration) {
        // use setIfAbsent instead of raw Jedis command
        boolean result = setRedis(key, expire);

        // retry if needed
        while ((!result) && retryTimes-- > 0) {
            try {
                log.debug("lock failed, retrying..." + retryTimes);
                Thread.sleep(retryDuration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            // use setIfAbsent instead of raw Jedis command
            result = setRedis(key, expire);
        }
        return result;
    }

    private boolean setRedis(String key, long expire) {
        try {
            String uuid = UUID.randomUUID().toString();
            lockFlag.set(uuid);

            // Spring Data Redis (Lettuce) setIfAbsent maps to SET NX PX command atomically
            // expire is in milliseconds, matching the original Jedis PX parameter
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, uuid, expire, TimeUnit.MILLISECONDS);

            return result != null && result;
        } catch (Exception e) {
            log.error("set redis occurred an exception", e);
        }
        return false;
    }

    public boolean unlock(String key) {
        boolean success = false;
        try {
            String flag = lockFlag.get();
            if (StringUtils.isEmpty(flag)) {
                // If lock flag is missing, technically we can't release the lock safely
                return false;
            }

            // Execute Lua script using RedisTemplate
            // Keys: Collections.singletonList(key)
            // Args: flag (the unique UUID)
            Long result = redisTemplate.execute(unlockScript, Collections.singletonList(key), flag);

            success = result != null && result > 0;
        } catch (Exception e) {
            log.error("release lock occurred an exception", e);
        } finally {
            if (success) {
                lockFlag.remove();
            }
        }
        return success;
    }
}
