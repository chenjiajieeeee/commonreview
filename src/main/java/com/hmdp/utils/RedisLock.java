package com.hmdp.utils;


import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RedisLock implements ILock{

    StringRedisTemplate  stringRedisTemplate;
    private final String lockName;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //初始化脚本
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private static final String LOCK_PREFIX="lock::";
    private static final String UUID= cn.hutool.core.lang.UUID.randomUUID().toString(true)+"-";
    public RedisLock(String lockName,StringRedisTemplate stringRedisTemplate){
        this.lockName=lockName;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        //获取当前线程的id
       String id = UUID+Thread.currentThread().getId();
        //避免自动拆箱的空指针
        return Boolean.TRUE.equals(stringRedisTemplate.
                opsForValue().
                setIfAbsent(LOCK_PREFIX+lockName,id,timeOutSec,TimeUnit.SECONDS));
    }

    @Override
    public void unlock() {
        //判断锁表示是否一致
        //获取线程标识
        //判断和释放之间有阻塞的可能性，不能保证原子性
        //调用LUA脚本,保证原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PREFIX+lockName),
                UUID+Thread.currentThread().getId());
    }

}
