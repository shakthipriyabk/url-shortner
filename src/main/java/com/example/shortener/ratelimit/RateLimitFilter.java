package com.example.shortener.ratelimit;

import com.example.shortener.config.ShortenerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Applies per-client token buckets before the request reaches a controller.
 * Creating links is throttled hard; following links is throttled leniently.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern REDIRECT_PATH = Pattern.compile("^/[0-9A-Za-z]{1,11}$");

    private final RedisRateLimiter limiter;
    private final ShortenerProperties props;

    public RateLimitFilter(RedisRateLimiter limiter, ShortenerProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        String name;
        ShortenerProperties.Bucket bucket;
        if (HttpMethod.POST.matches(method) && "/api/shorten".equals(path)) {
            name = "shorten";
            bucket = props.rateLimit().shorten();
        } else if (HttpMethod.GET.matches(method) && REDIRECT_PATH.matcher(path).matches()) {
            name = "redirect";
            bucket = props.rateLimit().redirect();
        } else {
            chain.doFilter(request, response);
            return;
        }

        // NOTE: behind a load balancer, getRemoteAddr() is the LB's address. Configure the servlet container to
        // trust X-Forwarded-For from known proxies only (e.g. server.tomcat.remoteip.*), never blindly.
        String key = "rl:" + name + ":" + request.getRemoteAddr();
        RedisRateLimiter.Decision decision = limiter.tryAcquire(key, bucket.capacity(), bucket.refillPerSecond());

        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(Math.max(1, decision.retryAfterSeconds())));
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded. Retry after %d second(s)."}"""
                    .formatted(Math.max(1, decision.retryAfterSeconds())));
            return;
        }
        chain.doFilter(request, response);
    }
}
