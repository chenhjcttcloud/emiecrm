package com.emie.crm.service;

import com.emie.crm.repository.CrmRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Copy only already persisted v1 intents; never enumerate approved orders to create new tasks. */
@Component
public final class JushuitanCreditPushMigration {
  private final CrmRepository db;
  public JushuitanCreditPushMigration(CrmRepository db) { this.db = db; }

  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ApplicationReadyEvent.class)
  public void migrate() {
    db.jdbc().update("""
        INSERT IGNORE INTO jst_credit_pushes(order_id,payload)
        SELECT order_id,JSON_EXTRACT(payload,'$.creditPush') FROM feishu_order_approvals
        WHERE JSON_VALID(payload) AND JSON_TYPE(JSON_EXTRACT(payload,'$.creditPush'))='OBJECT'
          AND JSON_EXTRACT(payload,'$.creditPush.policyVersion')=1
        """);
  }
}
