package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.emie.crm.common.exception.ApiException;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only, checkpointed catalog import. One leased page is committed atomically with its cursor. */
@Service
public class JushuitanCatalogJob {
  static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private final CrmRepository db;
  private final JushuitanClient client;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final String environment;
  private final String owner=UUID.randomUUID().toString();
  private Clock clock=Clock.systemUTC();
  private ScheduledExecutorService worker;

  public JushuitanCatalogJob(CrmRepository db,JushuitanClient client,ObjectMapper mapper,
      PlatformTransactionManager manager,@Value("${CRM_JST_ENV:sandbox}") String environment) {
    this.db=db;this.client=client;this.mapper=mapper;this.tx=new TransactionTemplate(manager);this.environment=environment;
  }
  @PostConstruct void launch() {
    worker=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"crm-jst-catalog");thread.setDaemon(true);return thread;});
    worker.scheduleWithFixedDelay(()->{try{tick();}catch(Exception ignored){/* Checkpoint retained; transient DB failure retries next tick. */}},10,1,TimeUnit.SECONDS);
  }
  @PreDestroy void stop() { if(worker!=null)worker.shutdownNow(); }
  private LocalDateTime now() {return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")).withNano(0);}
  private ObjectNode load(boolean lock) {
    var rows=db.rows("SELECT payload FROM jst_catalog_jobs WHERE environment=?"+(lock?" FOR UPDATE":""),environment);
    return rows.isEmpty()?null:(ObjectNode)db.parse(rows.getFirst().get("payload").toString());
  }
  private void store(ObjectNode job) {
    job.put("updatedAt",clock.instant().toString());
    db.jdbc().update("INSERT INTO jst_catalog_jobs(environment,payload) VALUES(?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload)",environment,db.encode(job));
  }
  public JsonNode status() {
    var job=load(false);
    if(job==null)return mapper.createObjectNode().put("status","IDLE").put("environment",environment);
    ObjectNode result=job.deepCopy();result.remove(List.of("verifySkus","owner","claim","leaseUntil"));
    result.put("progress",progress(job));return result;
  }
  static int progress(JsonNode job) {
    if(job.path("status").asText().equals("COMPLETE"))return 100;
    if(job.path("phase").asText().equals("VERIFY")) {
      int count=job.path("verifySkus").size();
      return count==0?99:95+(int)Math.min(4,4L*job.path("verifyIndex").asInt()/count);
    }
    long total=Duration.between(LocalDateTime.parse(job.path("from").asText(),FORMAT),LocalDateTime.parse(job.path("until").asText(),FORMAT)).toSeconds();
    long done=job.path("mode").asText().equals("FULL")
        ?Duration.between(LocalDateTime.parse(job.path("cursor").asText(),FORMAT),LocalDateTime.parse(job.path("until").asText(),FORMAT)).toSeconds()
        :Duration.between(LocalDateTime.parse(job.path("from").asText(),FORMAT),LocalDateTime.parse(job.path("cursor").asText(),FORMAT)).toSeconds();
    return total<=0?95:(int)Math.max(0,Math.min(94,95*done/total));
  }
  public JsonNode start(JsonNode input,Actor actor) {
    actor.require("business.write");require(client.configured(),"尚未配置聚水潭连接");
    tx.executeWithoutResult(s->{
      // A fixed row serializes concurrent first starts as well as resumes and worker claims.
      db.jdbc().update("INSERT IGNORE INTO jst_catalog_jobs(environment,payload) VALUES(?,?)",environment,"{}");
      var prior=load(true);
      require(prior==null||!Set.of("RUNNING","PAUSED","FAILED").contains(prior.path("status").asText()),"已有商品同步任务，请继续或完成当前任务");
      String mode=input.path("mode").asText("FULL");require(Set.of("FULL","INCREMENTAL").contains(mode),"同步方式无效");
      LocalDateTime begin;
      if(mode.equals("FULL")) {
        try{begin=LocalDate.parse(input.path("startDate").asText("2000-01-01")).atStartOfDay();}
        catch(Exception e){throw new ApiException("同步起始日期无效");}
        require(!begin.isBefore(LocalDate.of(1900,1,1).atStartOfDay())&&begin.isBefore(now()),"请选择1900年以后且早于当前时间的起始日期");
      } else {
        require(prior!=null&&prior.hasNonNull("watermark"),"请先完成首次全量同步");
        begin=LocalDateTime.parse(prior.path("watermark").asText(),FORMAT).minusMinutes(5);
      }
      var job=newJob(mode,begin,now(),actor.name(),prior);
      store(job);db.audit(actor.name(),"启动聚水潭商品"+(mode.equals("FULL")?"全量":"增量")+"同步");
    });
    return status();
  }
  private ObjectNode newJob(String mode,LocalDateTime from,LocalDateTime until,String operator,JsonNode previous) {
    var job=mapper.createObjectNode().put("id",UUID.randomUUID().toString()).put("environment",environment)
        .put("mode",mode).put("status","RUNNING").put("phase","READ").put("from",FORMAT.format(from))
        .put("until",FORMAT.format(until)).put("cursor",FORMAT.format(mode.equals("FULL")?until:from)).put("page",1).put("processedRows",0)
        .put("verifyIndex",0).put("operator",operator).put("startedAt",clock.instant().toString()).put("leaseUntil",0);
    if(previous!=null)for(String key:List.of("watermark","lastFullCompletedAt","lastVerifiedAt"))if(previous.has(key))job.set(key,previous.path(key));
    var verify=job.putArray("verifySkus");
    boolean verifyNeeded=mode.equals("FULL")||previous==null||clock.millis()-previous.path("lastVerifiedAt").asLong()>86400000L;
    if(verifyNeeded) {
      var skus=new TreeSet<String>();
      for(var p:db.state(false).path("products"))if(p.path("source").asText().equals("jushuitan")&&p.path("jstEnvironment").asText().equals(environment))skus.add(p.path("jstSku").asText());
      skus.forEach(verify::add);
      job.put("willVerify",true);
    }
    return job;
  }
  public JsonNode control(String action,Actor actor) {
    actor.require("business.write");
    tx.executeWithoutResult(s->{
      var job=load(true);require(job!=null,"暂无商品同步任务");
      if(action.equals("pause")) {require(job.path("status").asText().equals("RUNNING"),"当前任务未在运行");job.put("status","PAUSED");}
      else if(action.equals("resume")) {require(Set.of("PAUSED","FAILED").contains(job.path("status").asText()),"当前任务无需继续");job.put("status","RUNNING");job.remove("error");}
      else throw new ApiException("同步操作无效");
      job.put("leaseUntil",0);job.remove("owner");store(job);
    });return status();
  }
  private ObjectNode claim() {
    return tx.execute(s->{
      var job=load(true);if(job==null)return null;
      if(job.path("status").asText().equals("COMPLETE")&&clock.millis()-job.path("completedMillis").asLong()>=600000L) {
        job=newJob("INCREMENTAL",LocalDateTime.parse(job.path("watermark").asText(),FORMAT).minusMinutes(5),now(),"系统自动同步",job);
      }
      if(!job.path("status").asText().equals("RUNNING")||job.path("leaseUntil").asLong()>clock.millis())return null;
      job.put("owner",owner).put("claim",UUID.randomUUID().toString()).put("leaseUntil",clock.millis()+120000L);store(job);return job.deepCopy();
    });
  }
  private boolean sameLease(JsonNode current,JsonNode claimed) {
    return current!=null&&current.path("id").equals(claimed.path("id"))&&current.path("owner").asText().equals(owner)
        &&current.path("claim").equals(claimed.path("claim"))&&current.path("leaseUntil").equals(claimed.path("leaseUntil"))&&current.path("status").asText().equals("RUNNING");
  }
  void tick() {
    if(!client.configured()||Thread.currentThread().isInterrupted())return;
    var claimed=claim();if(claimed==null)return;
    try {
      var params=parameters(claimed);
      var response=client.query("/open/sku/query",params);
      require(response.path("code").asInt(-1)==0,"聚水潭商品查询失败（代码 "+response.path("code").asText()+"），可继续重试");
      require(response.path("data").path("datas").isArray()&&response.path("data").path("has_next").isBoolean(),"商品分页结果格式异常，已保留断点");
      var data=response.path("data");
      tx.executeWithoutResult(s->{
        var current=load(true);if(!sameLease(current,claimed))return; // Paused/replaced requests cannot commit late results.
        var rows=(ArrayNode)data.path("datas");
        boolean verify=claimed.path("phase").asText().equals("VERIFY");
        require(!verify||!data.path("has_next").asBoolean(),"SKU核对返回额外分页，已保留断点");
        if(!rows.isEmpty()||verify) {
          var state=db.state(true);require(state.isObject(),"CRM业务数据未初始化");
          ObjectNode next=state.deepCopy();var scope=new HashSet<String>();
          if(verify)for(String sku:params.get("sku_ids").toString().split(","))scope.add(sku);
          else for(var row:rows)scope.add(row.path("sku_id").asText());
          next.set("products",JushuitanCatalog.merge(mapper,next.path("products"),rows,environment,scope));
          next.put("productSource","jushuitan").put("productsSyncedAt",clock.instant().toString()).put("revision",state.path("revision").asLong()+1);
          db.state(next);current.put("catalogRevision",next.path("revision").asLong());
        }
        advance(current,data.path("has_next").asBoolean(),rows.size(),clock);
        current.put("leaseUntil",0);current.remove("owner");store(current);
      });
    } catch(Exception error) {
      boolean interrupted=Thread.interrupted();
      try {
        tx.executeWithoutResult(s->{var current=load(true);if(!sameLease(current,claimed))return;
          if(!interrupted) current.put("status","FAILED").put("error",error instanceof ApiException?error.getMessage():"同步暂未完成，断点已保留，请继续同步");
          // A normal service shutdown is resumable work, not a permanent failed job.
          current.put("leaseUntil",0);current.remove("owner");store(current);});
      } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
  }
  static Map<String,Object> parameters(JsonNode job) {
    if(job.path("phase").asText().equals("VERIFY")) {
      var skus=new ArrayList<String>();int start=job.path("verifyIndex").asInt();
      for(int i=start;i<Math.min(start+20,job.path("verifySkus").size());i++)skus.add(job.path("verifySkus").get(i).asText());
      require(!skus.isEmpty(),"商品核对断点无效");return Map.of("sku_ids",String.join(",",skus),"page_index",1,"page_size",100);
    }
    var cursor=LocalDateTime.parse(job.path("cursor").asText(),FORMAT);
    var from=LocalDateTime.parse(job.path("from").asText(),FORMAT);var until=LocalDateTime.parse(job.path("until").asText(),FORMAT);
    boolean full=job.path("mode").asText().equals("FULL");
    var begin=full?(cursor.minusDays(7).isAfter(from)?cursor.minusDays(7):from):cursor;
    var end=full?cursor:(cursor.plusDays(7).isBefore(until)?cursor.plusDays(7):until);
    return Map.of("modified_begin",FORMAT.format(begin),"modified_end",FORMAT.format(end),"date_field",full?"created":"modified",
        "page_index",job.path("page").asInt(),"page_size",100);
  }
  static void advance(ObjectNode job,boolean hasNext,int count,Clock clock) {
    job.put("processedRows",job.path("processedRows").asLong()+count);
    if(job.path("phase").asText().equals("VERIFY")) {
      job.put("verifyIndex",job.path("verifyIndex").asInt()+20);
      if(job.path("verifyIndex").asInt()<job.path("verifySkus").size())return;
    } else if(hasNext) {
      require(job.path("page").asInt()<10000,"当前时间段商品页数超限，请联系管理员");job.put("page",job.path("page").asInt()+1);return;
    } else {
      boolean full=job.path("mode").asText().equals("FULL");
      String boundary=parameters(job).get(full?"modified_begin":"modified_end").toString();job.put("cursor",boundary).put("page",1);
      if(!boundary.equals(job.path(full?"from":"until").asText()))return;
      if(!job.path("verifySkus").isEmpty()) {job.put("phase","VERIFY");return;}
    }
    job.put("status","COMPLETE").put("watermark",job.path("until").asText()).put("completedAt",clock.instant().toString()).put("completedMillis",clock.millis());
    if(job.path("mode").asText().equals("FULL"))job.put("lastFullCompletedAt",clock.instant().toString());
    if(job.path("willVerify").asBoolean())job.put("lastVerifiedAt",clock.millis());
  }
}
