package com.zhiyuan.college.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA history 路由回退：前端 Vue Router 使用 history 模式，直接访问/刷新子路由
 * （如 /rank、/schools/41、/majors/土木工程）时后端必须回退到 index.html，
 * 否则返回 RESOURCE_NOT_FOUND JSON，用户看到白屏或裸错误框。
 *
 * <p>仅枚举前端路由白名单；/api/** 由各 REST 控制器优先匹配，静态资源（带点号的
 * js/css/图片路径）由默认资源处理器处理，均不会落入本控制器。
 */
@Controller
public class SpaForwardController {

    private static final String INDEX = "forward:/index.html";

    @GetMapping({
            "/",
            "/login",
            "/profile-setup",
            "/schools",
            "/majors",
            "/volunteer",
            "/choose",
            "/rank",
            "/segments",
            "/enroll",
            "/news",
            "/recommend",
            "/agent",
            "/history",
            "/plans",
            "/admin",
            "/schools/{code}",
            "/majors/{code}",
            "/news/{code}",
            "/plans/{code}"
    })
    public String forwardSpaRoutes() {
        return INDEX;
    }
}
