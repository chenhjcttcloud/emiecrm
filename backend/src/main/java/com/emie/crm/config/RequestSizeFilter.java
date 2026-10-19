package com.emie.crm.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeFilter extends OncePerRequestFilter {
  private static final long MAX = 30L * 1024 * 1024;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > MAX) {
      response.setStatus(413);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write("{\"error\":\"数据超过30MB\"}");
      return;
    }
    var wrapped =
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() throws IOException {
            var input = super.getInputStream();
            return new ServletInputStream() {
              long count;

              private void limit(int n) throws IOException {
                if (n > 0 && (count += n) > MAX) throw new IOException("数据超过30MB");
              }

              public int read() throws IOException {
                int n = input.read();
                limit(n < 0 ? 0 : 1);
                return n;
              }

              public int read(byte[] b, int off, int len) throws IOException {
                int n = input.read(b, off, len);
                limit(n);
                return n;
              }

              public boolean isFinished() {
                return input.isFinished();
              }

              public boolean isReady() {
                return input.isReady();
              }

              public void setReadListener(ReadListener listener) {
                input.setReadListener(listener);
              }
            };
          }
        };
    chain.doFilter(wrapped, response);
  }
}
