package com.emie.crm.security;

import com.lark.oapi.event.EventDispatcher;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps the Feishu event channel separate from OAuth and HTTP availability. */
@Component
public class FeishuLongConnection {
  private static final Logger log=LoggerFactory.getLogger(FeishuLongConnection.class);
  private final FeishuClient feishu;
  private final boolean enabled;
  private volatile String status="disabled";
  private volatile boolean closed;
  private volatile com.lark.oapi.ws.Client connection;
  private ScheduledExecutorService worker;
  public FeishuLongConnection(FeishuClient feishu,@Value("${CRM_FEISHU_LONG_CONNECTION:0}") String enabled) {
    this.feishu=feishu;this.enabled="1".equals(enabled);
  }
  public String status(){return status;}
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if(worker!=null || closed || !enabled)return;
    if(!feishu.ready()){status="unconfigured";return;}
    worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"crm-feishu-connection");t.setDaemon(true);return t;});
    status="connecting";
    worker.scheduleWithFixedDelay(this::connect,0,30,TimeUnit.SECONDS);
  }
  private void connect() {
    if(closed || connection!=null)return;
    try {
      var channel=feishu.longConnection(EventDispatcher.newBuilder("","").build(),
          ()->{if(!closed)status="reconnecting";},()->{if(!closed)status="connected";});
      connection=channel;
      if(closed){channel.close();return;}
      channel.start();
      channel.awaitReady(15_000);
      if(!closed){status="connected";log.info("Feishu long connection established");}
    } catch(Exception e) {
      var current=connection;connection=null;
      if(current!=null)current.close();
      if(!closed){status="retrying";log.warn("Feishu long connection unavailable; retry scheduled ({})",e.getClass().getSimpleName());}
    }
  }
  @PreDestroy
  public synchronized void stop() {
    closed=true;status="stopped";
    if(worker!=null)worker.shutdownNow();
    if(connection!=null){connection.close();connection=null;}
  }
}
