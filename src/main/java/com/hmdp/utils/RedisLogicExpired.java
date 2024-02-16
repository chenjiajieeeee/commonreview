package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Objects;

@Data
public class RedisLogicExpired {
    private Objects data;
    private LocalDateTime expired;
}
