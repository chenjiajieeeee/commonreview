package com.hmdp.incetercpter;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.UserCommon.LOG_TIME_OUT;

public class AllInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    public AllInterceptor(StringRedisTemplate redisTemplate){
        this.redisTemplate=redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1、获取请求头token
        String token;
        Map<Object,Object> userInfo;
        UserDTO userDTO;
        if( (token= request.getHeader("authorization"))==null)return true;
        //2、token在redis不存在，说明token非法，返回false
        if((userInfo=redisTemplate.opsForHash().entries(token)).isEmpty()) return true;

        userDTO= BeanUtil.fillBeanWithMap(userInfo,new UserDTO(),false);

        //2、刷新token
        redisTemplate.expire(token,LOG_TIME_OUT, TimeUnit.MINUTES);
        UserHolder.saveUser(userDTO);
        return true;
    }
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) {
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        UserHolder.removeUser();
    }

}
