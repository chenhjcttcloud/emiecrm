package com.emie.crm.service;

import static com.emie.crm.common.exception.ApiException.require;
import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {
  private final CrmRepository db;
  public PaymentMethodService(CrmRepository db) { this.db = db; }
  public List<Map<String,Object>> list() {
    return db.rows("SELECT id,name,status,sort_order AS sortOrder FROM payment_methods ORDER BY sort_order,id");
  }
  @Transactional
  public void save(JsonNode b, Actor actor) {
    actor.require("system.manage");
    db.state(true);
    String id=b.path("id").asText(""), name=b.path("name").asText("").trim(), status=b.path("status").asText("启用");
    require(!name.isEmpty() && name.length()<=50 && !name.equals("预收款核销"), "名称须为1–50字，不能使用预收款核销");
    require(List.of("启用","停用").contains(status), "状态无效");
    require(b.path("sortOrder").canConvertToInt() && b.path("sortOrder").isIntegralNumber() && b.path("sortOrder").asInt()>0, "排序须为正整数");
    if(!id.isEmpty()) require(!db.rows("SELECT id FROM payment_methods WHERE id=?",id).isEmpty(), "收款方式不存在");
    require(db.rows("SELECT id FROM payment_methods WHERE name=? AND id<>?",name,id).isEmpty(), "收款方式名称已存在");
    if(id.isEmpty()) id=UUID.randomUUID().toString();
    db.jdbc().update("INSERT INTO payment_methods(id,name,status,sort_order) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),status=VALUES(status),sort_order=VALUES(sort_order)",id,name,status,b.path("sortOrder").asInt());
    db.audit(actor.name(), "保存收款方式 "+name+"（"+status+"）");
  }
}
