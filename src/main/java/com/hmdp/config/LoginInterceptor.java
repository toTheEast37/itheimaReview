package com.hmdp.config;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.javassist.bytecode.analysis.Util;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.swing.plaf.BorderUIResource;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //获取session
        HttpSession session = request.getSession();
        //获取session中的用户
        Object user = session.getAttribute("user");
        //判断放行
        if(user == null){
            response.setStatus(401);
            return false;
        }

        //保存treadLocal
        UserHolder.saveUser((UserDTO) user);
        return true;
    }
}
