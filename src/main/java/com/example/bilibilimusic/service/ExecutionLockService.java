package com.example.bilibilimusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Agent 执行锁服务
 * 
 * 功能：
 * 1. 防止同一 playlist 并发执行
 * 2. 防止重复写数据库
 * 3. 自动过期防死锁（TTL 机制）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionLockService {
    
    private final RedissonClient redissonClient;
    
    // 锁等待时间（秒）
    private static final long WAIT_TIME = 5;
    
    // 锁自动释放时间（秒，防死锁）
    private static final long LEASE_TIME = 300; // 5分钟
    
    /**
     * 尝试获取执行锁
     * 
     * @param playlistId 播放列表ID
     * @return 是否成功获取锁
     */
    public boolean tryLock(Long playlistId) {
        String lockKey = getLockKey(playlistId);
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            boolean acquired = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
            
            if (acquired) {
                log.info("[ExecutionLock] 获取执行锁成功: playlistId={}", playlistId);
            } else {
                log.warn("[ExecutionLock] 获取执行锁失败（已被占用）: playlistId={}", playlistId);
            }
            
            return acquired;
        } catch (InterruptedException e) {
            log.error("[ExecutionLock] 获取锁时被中断: playlistId={}", playlistId, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 释放执行锁
     */
    public void unlock(Long playlistId) {
        String lockKey = getLockKey(playlistId);
        RLock lock = redissonClient.getLock(lockKey);
        
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("[ExecutionLock] 释放执行锁: playlistId={}", playlistId);
        } else {
            log.warn("[ExecutionLock] 尝试释放非本线程持有的锁: playlistId={}", playlistId);
        }
    }
    
    /**
     * 强制释放锁（谨慎使用）
     */
    public void forceUnlock(Long playlistId) {
        String lockKey = getLockKey(playlistId);
        RLock lock = redissonClient.getLock(lockKey);
        
        if (lock.isLocked()) {
            lock.forceUnlock();
            log.warn("[ExecutionLock] 强制释放锁: playlistId={}", playlistId);
        }
    }
    
    /**
     * 检查是否已锁定
     */
    public boolean isLocked(Long playlistId) {
        String lockKey = getLockKey(playlistId);
        RLock lock = redissonClient.getLock(lockKey);
        return lock.isLocked();
    }
    
    /**
     * 执行带锁的操作
     * 
     * @param playlistId 播放列表ID
     * @param action 要执行的操作
     * @return 是否成功执行（获取锁失败返回false）
     */
    public boolean executeWithLock(Long playlistId, Runnable action) {
        if (tryLock(playlistId)) {
            try {
                action.run();
                return true;
            } finally {
                unlock(playlistId);
            }
        }
        return false;
    }
    
    /**
     * 执行带锁的操作（带返回值）
     */
    public <T> T executeWithLock(Long playlistId, java.util.function.Supplier<T> action, T defaultValue) {
        if (tryLock(playlistId)) {
            try {
                return action.get();
            } finally {
                unlock(playlistId);
            }
        }
        return defaultValue;
    }
    
    /**
     * 生成锁 Key
     */
    private String getLockKey(Long playlistId) {
        return "agent:lock:" + playlistId;
    }
}
