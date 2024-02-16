package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 该工具类用于生成全局唯一id
 */
@Component
public class RedisWorker {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    private static final  long BEGIN_Time_Stamp= 1705622400;
    private static final String DATE_FORMAT="yyyyMMdd";
    private static final String KEY_PREFIX_INC="inc::id::";
    //时间戳移动的位数
    private static final int BIT=32;
    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second-BEGIN_Time_Stamp;
        //生成序列号
        //获取当前日期，精确到天
        final String date =now.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        //以天为单位递增，如果key不存在，会自动传教一个key
        long incrementId = stringRedisTemplate.opsForValue().increment(KEY_PREFIX_INC + keyPrefix + date);
        //拼接返回
        return timestamp<<BIT|incrementId;
    }

}
