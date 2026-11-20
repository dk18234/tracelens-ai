package dev.dineshrama.tracelens;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
  private final TraceProperties config;
  public RequestGuard(TraceProperties config) { this.config=config; }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    response.setHeader("X-Content-Type-Options","nosniff");
    response.setHeader("Referrer-Policy","same-origin");
    response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'");
    if(request.getRequestURI().startsWith("/api/")) {
      String token=request.getHeader("X-Admin-Token");
      if(token==null || !MessageDigest.isEqual(config.adminToken().getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) {
        error(response,401,"ADMIN_TOKEN_REQUIRED","Enter your operator token to use the local AI backend.");return;
      }
      if(request.getMethod().equals("POST")) {
        byte[] body=request.getInputStream().readNBytes(16385);
        if(body.length>16384){error(response,413,"PAYLOAD_TOO_LARGE","Request bodies are limited to 16 KiB.");return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
          @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read(){return input.read();}
              public boolean isFinished(){return input.available()==0;}
              public boolean isReady(){return true;}
              public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
          }
          @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);return;
      }
    }
    chain.doFilter(request,response);
  }
  private void error(HttpServletResponse response,int status,String code,String message)throws IOException {
    response.setStatus(status);response.setContentType("application/json");response.getWriter().write(Json.write(java.util.Map.of("code",code,"message",message)));
  }
}
