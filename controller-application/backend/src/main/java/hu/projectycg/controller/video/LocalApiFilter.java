package hu.projectycg.controller.video;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent unrelated web pages from submitting local file paths to the API. */
@Component
public class LocalApiFilter extends OncePerRequestFilter {
    private static final Set<String> ORIGINS = Set.of("ycg://app", "http://127.0.0.1:5173",
            "http://127.0.0.1:4173", "http://localhost:5173", "http://localhost:4173",
            "http://127.0.0.1:8080", "http://localhost:8080");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String origin = request.getHeader("Origin");
        if (request.getRequestURI().startsWith("/api/chapters") && origin != null && !ORIGINS.contains(origin)) {
            response.sendError(403, "Only the local YCG application may start processing.");
            return;
        }
        chain.doFilter(request, response);
    }
}
