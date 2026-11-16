package com.smarttrip.mcp;

import com.smarttrip.common.UserContext;
import com.smarttrip.config.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Servlet filter also protects functional MCP routes and asynchronous transport requests. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+20)
public class TravelMcpSecurityFilter extends OncePerRequestFilter {
  private final TravelMcpProperties properties;private final JwtAuthInterceptor jwt;private final StringRedisTemplate redis;
  private record Session(long user,long expires) {}
  private final Map<String,Session> sessions=new ConcurrentHashMap<>();
  public TravelMcpSecurityFilter(TravelMcpProperties properties,JwtAuthInterceptor jwt,StringRedisTemplate redis){this.properties=properties;this.jwt=jwt;this.redis=redis;}
  @Override protected boolean shouldNotFilter(HttpServletRequest req){String p=req.getServletPath();if(p==null||p.isEmpty())p=req.getRequestURI();return !(p.startsWith("/api/mcp")||p.startsWith("/mcp")||p.equals("/sse"));}
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
    if(!properties.isEnabled()||!TravelMcpConfiguration.ENDPOINT.equals(req.getRequestURI())){res.setStatus(404);return;}
    if(req.getContentLengthLong()>32768){res.setStatus(413);return;}
    // Cross-origin browser requests cannot use this loopback developer endpoint.
    String origin=req.getHeader("Origin");
    if(origin!=null){res.setStatus(403);return;}
    if("OPTIONS".equals(req.getMethod())){res.setStatus(405);return;}
    try{
      if(!jwt.preHandle(req,res,null))return;
      Long user=UserContext.getUserId();if(user==null){res.setStatus(401);return;}
      Long count;
      try{count=redis.execute(new DefaultRedisScript<Long>("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],60) end;return n",Long.class),List.of("smarttrip:mcp:rate:"+user));}
      catch(RuntimeException e){res.setStatus(503);return;}
      if(count==null){res.setStatus(503);return;}if(count>properties.getRequestsPerMinute()){res.setStatus(429);return;}
      long now=System.currentTimeMillis();sessions.entrySet().removeIf(e->e.getValue().expires()<now);
      String sessionId=req.getHeader("Mcp-Session-Id");
      if(sessionId!=null){var session=sessions.get(sessionId);if(session==null){res.setStatus(404);return;}if(session.user()!=user.longValue()){res.setStatus(403);return;}sessions.put(sessionId,new Session(user,now+600000));}
      else if(sessions.size()>=256){res.setStatus(503);return;}
      req.setAttribute(TravelMcpConfiguration.USER,user);
      var wrapped=new HttpServletResponseWrapper(res){
        @Override public void addHeader(String name,String value){
          if("Mcp-Session-Id".equalsIgnoreCase(name)&&value!=null)sessions.put(value,new Session(user,System.currentTimeMillis()+600000));
          super.addHeader(name,value);
        }
        @Override public void setHeader(String name,String value){
          if("Mcp-Session-Id".equalsIgnoreCase(name)&&value!=null)sessions.put(value,new Session(user,System.currentTimeMillis()+600000));
          super.setHeader(name,value);
        }
      };
      chain.doFilter(req,wrapped);
      if("DELETE".equals(req.getMethod())&&sessionId!=null&&res.getStatus()<400)sessions.remove(sessionId);
    }finally{UserContext.clear();}
  }
}
