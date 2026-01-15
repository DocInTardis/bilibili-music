package com.example.bilibilimusic.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类 - 分布式锁和对象
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${redisson.pool-size:50}")
    private int poolSize;

    @Value("${redisson.min-idle-size:10}")
    private int minIdleSize;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // 单机模式
        SingleServerConfig single = config.useSingleServer()
            .setAddress(String.format("redis://%s:%d", redisHost, redisPort))
            .setDatabase(redisDatabase)
            .setConnectionPoolSize(poolSize)
            .setConnectionMinimumIdleSize(minIdleSize)
            .setIdleConnectionTimeout(10000)
            .setConnectTimeout(5000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500);

        if (redisPassword != null && !redisPassword.isBlank()) {
            single.setPassword(redisPassword);
        }
        
        return Redisson.create(config);
    }
}
