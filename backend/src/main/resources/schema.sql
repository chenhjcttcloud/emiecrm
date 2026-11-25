CREATE TABLE IF NOT EXISTS state(id TINYINT PRIMARY KEY,payload LONGTEXT NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jst_shipments(order_id VARCHAR(191) PRIMARY KEY,payload LONGTEXT NOT NULL,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX jst_updated(updated_at)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jst_credit_pushes(order_id VARCHAR(191) PRIMARY KEY,payload LONGTEXT NOT NULL,updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),INDEX jst_credit_push_updated(updated_at)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jst_document_sales(
  environment VARCHAR(16) NOT NULL,
  order_id VARCHAR(191) NOT NULL,
  shop_id VARCHAR(19) NOT NULL,
  o_id VARCHAR(19) NOT NULL,
  so_id VARCHAR(191) NOT NULL,
  sale_metadata JSON NULL,
  PRIMARY KEY(environment,order_id,o_id),
  UNIQUE KEY jst_document_sales_o_id(environment,o_id),
  UNIQUE KEY jst_document_sales_so_id(environment,shop_id,so_id)
) ENGINE=InnoDB;
SET @jst_sales_primary_contains_o_id = EXISTS(
  SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='jst_document_sales' AND index_name='PRIMARY' AND column_name='o_id'
);
SET @jst_sales_has_metadata = EXISTS(
  SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='jst_document_sales' AND column_name='sale_metadata'
);
SET @jst_sales_changes = CONCAT_WS(', ',
  IF(@jst_sales_primary_contains_o_id, NULL, 'DROP PRIMARY KEY, ADD PRIMARY KEY(environment,order_id,o_id)'),
  IF(@jst_sales_has_metadata, NULL, 'ADD COLUMN sale_metadata JSON NULL')
);
SET @jst_sales_migration = IF(@jst_sales_changes='', 'DO 0', CONCAT('ALTER TABLE jst_document_sales ', @jst_sales_changes));
PREPARE jst_sales_migration_stmt FROM @jst_sales_migration;
EXECUTE jst_sales_migration_stmt;
DEALLOCATE PREPARE jst_sales_migration_stmt;
CREATE TABLE IF NOT EXISTS jst_document_purchases(
  environment VARCHAR(16) NOT NULL,
  order_id VARCHAR(191) NOT NULL,
  po_id VARCHAR(19) NOT NULL,
  PRIMARY KEY(environment,order_id,po_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO state(id,payload) VALUES(1,NULL);
CREATE TABLE IF NOT EXISTS order_number_reservations(
  actor_name VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
  scope VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  number_day DATE NOT NULL,
  sequence_no BIGINT NOT NULL,
  order_number VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  order_id VARCHAR(191) COLLATE utf8mb4_bin NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(actor_name,scope,request_id),
  UNIQUE KEY order_number_unique(order_number),
  UNIQUE KEY order_number_day_sequence(number_day,sequence_no),
  UNIQUE KEY order_number_order_id(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS receipts(seq BIGINT AUTO_INCREMENT PRIMARY KEY,id VARCHAR(64) NOT NULL UNIQUE,payload LONGTEXT NOT NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS users(name VARCHAR(191) PRIMARY KEY,salt VARCHAR(64) NOT NULL,password VARCHAR(128) NOT NULL,role VARCHAR(32) NOT NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS audit(seq BIGINT AUTO_INCREMENT PRIMARY KEY,id VARCHAR(64) NOT NULL UNIQUE,payload LONGTEXT NOT NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS roles(name VARCHAR(32) PRIMARY KEY,description VARCHAR(255) NOT NULL,permissions JSON NOT NULL,builtin BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS departments(id VARCHAR(64) PRIMARY KEY,name VARCHAR(100) NOT NULL,parent_id VARCHAR(64) NULL,manager VARCHAR(100) NOT NULL DEFAULT '',status VARCHAR(16) NOT NULL DEFAULT '启用',created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS user_profiles(name VARCHAR(191) PRIMARY KEY,display_name VARCHAR(100) NOT NULL,email VARCHAR(191) NOT NULL DEFAULT '',phone VARCHAR(32) NOT NULL DEFAULT '',department_id VARCHAR(64) NULL,status VARCHAR(16) NOT NULL DEFAULT '启用',created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS payment_methods(id VARCHAR(64) PRIMARY KEY,name VARCHAR(50) NOT NULL UNIQUE,status VARCHAR(16) NOT NULL DEFAULT '启用',sort_order INT NOT NULL DEFAULT 0) ENGINE=InnoDB;
INSERT IGNORE INTO payment_methods(id,name,status,sort_order) SELECT 'bank','银行转账','启用',1 WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE id='bank');
INSERT IGNORE INTO payment_methods(id,name,status,sort_order) SELECT 'alipay','支付宝','启用',2 WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE id='alipay');
INSERT IGNORE INTO payment_methods(id,name,status,sort_order) SELECT 'wechat','微信','启用',3 WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE id='wechat');
INSERT IGNORE INTO payment_methods(id,name,status,sort_order) SELECT 'cash','现金','启用',4 WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE id='cash');

CREATE TABLE IF NOT EXISTS feishu_identities(app_id VARCHAR(100) NOT NULL,open_id VARCHAR(191) NOT NULL,user_name VARCHAR(191) NOT NULL,feishu_user_id VARCHAR(191) NOT NULL DEFAULT '',PRIMARY KEY(app_id,open_id),UNIQUE KEY unique_user_app(app_id,user_name)) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS assets(id CHAR(64) PRIMARY KEY,content_type VARCHAR(32) NOT NULL,content MEDIUMBLOB NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jst_catalog_jobs(environment VARCHAR(16) PRIMARY KEY,payload LONGTEXT NOT NULL,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS audit_archives(id VARCHAR(64) PRIMARY KEY,quarter VARCHAR(7) NOT NULL,record_count INT NOT NULL,raw_bytes INT NOT NULL,compressed_bytes INT NOT NULL,sha256 CHAR(64) NOT NULL,content LONGBLOB NOT NULL,created_at VARCHAR(32) NOT NULL,INDEX audit_archive_quarter(quarter)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS dashboard_targets(month_key CHAR(7) PRIMARY KEY,amount DECIMAL(14,2) NULL,revision BIGINT NOT NULL DEFAULT 0,updated_at VARCHAR(32) NULL,updated_by VARCHAR(191) NULL) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS sales_targets(month_key CHAR(7) NOT NULL,owner VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,amount DECIMAL(14,2) NULL,revision BIGINT NOT NULL DEFAULT 0,updated_at VARCHAR(32) NULL,updated_by VARCHAR(191) NULL,PRIMARY KEY(month_key,owner)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS sales_target_scope(id TINYINT PRIMARY KEY,mode VARCHAR(16) NOT NULL,department_ids JSON NOT NULL,revision BIGINT NOT NULL DEFAULT 0,updated_at VARCHAR(32) NULL,updated_by VARCHAR(191) NULL,CHECK(id=1),CHECK(mode IN ('all','selected'))) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS feishu_order_approvals(
  order_id VARCHAR(191) PRIMARY KEY,
  status VARCHAR(16) NOT NULL,
  instance_code VARCHAR(191) NULL,
  payload LONGTEXT NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY feishu_approval_poll(status,updated_at),
  UNIQUE KEY feishu_approval_instance(instance_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS feishu_receipt_approvals(
  id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(16) NOT NULL,
  instance_code VARCHAR(191) NULL,
  payload LONGTEXT NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY feishu_receipt_poll(status,updated_at),
  UNIQUE KEY feishu_receipt_instance(instance_code)
) ENGINE=InnoDB;
