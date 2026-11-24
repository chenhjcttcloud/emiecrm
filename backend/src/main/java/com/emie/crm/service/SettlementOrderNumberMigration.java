package com.emie.crm.service;

import com.emie.crm.repository.CrmRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Idempotent backfill; it neither edits external approval snapshots nor starts an approval. */
@Component
@Order(10)
public class SettlementOrderNumberMigration implements ApplicationRunner {
  private final CrmRepository db;
  private final TransactionTemplate transactions;

  public SettlementOrderNumberMigration(CrmRepository db, PlatformTransactionManager manager) {
    this.db = db;
    this.transactions = new TransactionTemplate(manager);
  }

  @Override public void run(ApplicationArguments args) {
    transactions.executeWithoutResult(transaction -> {
      var state = db.state(true);
      if (!state.isObject()) return;
      ObjectNode next = state.deepCopy();
      int count = SettlementOrderNumbers.assignMissing(next);
      if (count == 0) return;
      next.put("revision", state.path("revision").asLong() + 1);
      db.state(next);
      db.audit("系统", "补齐结算补录业务编号", db.object().put("type", "SETTLEMENT_NUMBERS_ASSIGNED").put("orders", count));
    });
  }
}
