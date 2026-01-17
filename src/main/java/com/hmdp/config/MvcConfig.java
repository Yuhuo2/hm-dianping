package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    // 由于@Configuration的存在，这里可以直接注入
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        // 手动构建LoginInterceptor对象, 并传入StringRedisTemplate对象
        // 此处LoginInterceptor 是你手动new的.不是 Spring 创建的，不在 Spring 容器中
        // 谁在容器里，谁就能注入，谁调用谁注入
        registry.addInterceptor(new LoginInterceptor(stringRedisTemplate))
//                放行路径
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                );
    }
}
