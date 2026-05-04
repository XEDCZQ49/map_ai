package com.example.mapaiserver.redis.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisDataService {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisDataService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, String value, Long ttlSeconds) {
        if (ttlSeconds != null && ttlSeconds > 0) {
            stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
            return;
        }
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public Boolean delete(String key) {
        return stringRedisTemplate.delete(key);
    }
}

