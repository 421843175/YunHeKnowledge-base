package com.company.aiservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class JwtUploadInterceptor implements HandlerInterceptor {

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    public JwtUploadInterceptor(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
        this.jwtTokenService = jwtTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        try {
            JwtUserClaims claims = jwtTokenService.parseRequest(request);
            boolean isDocUpload = "/api/docs/upload".equals(request.getRequestURI());
            if (claims.role() == 2 && HttpMethod.POST.matches(request.getMethod()) && isDocUpload) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "职工无权限上传企业文档");
                return false;
            }
            request.setAttribute(SecurityConstants.REQUEST_CLAIMS_KEY, claims);
            return true;
        } catch (JwtAuthException ex) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            return false;
        }
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}

