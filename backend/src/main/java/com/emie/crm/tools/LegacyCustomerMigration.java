package com.emie.crm.tools;

import com.emie.crm.repository.CrmRepository;
import com.emie.crm.security.Actor;
import com.emie.crm.service.AssetService;
import com.emie.crm.service.BusinessService;
import com.emie.crm.service.LegacyCustomerMigrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only CLI. Does not start HTTP, application initializers, Feishu, or ERP jobs. */
public final class LegacyCustomerMigration {
  private LegacyCustomerMigration() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !Set.of("preview", "commit").contains(args[0]))
      throw new IllegalArgumentException("Usage: preview|commit input.json expectedDatabase expectedSourceSHA256");
    String database = env("CRM_DB_NAME", "emiecrm");
    if (!database.equals(args[2]) || !database.matches("[a-zA-Z0-9_]+"))
      throw new IllegalArgumentException("Database does not match explicit migration target");
    String host = env("CRM_DB_HOST", "127.0.0.1"), port = env("CRM_DB_PORT", "3306");
    if (!host.matches("[a-zA-Z0-9_.:-]+") || !port.matches("[0-9]{1,5}"))
      throw new IllegalArgumentException("Invalid database connection settings");
    ObjectMapper mapper = new ObjectMapper();
    Path inputPath = Path.of(args[1]);
    if (Files.size(inputPath) > 10 * 1024 * 1024)
      throw new IllegalArgumentException("Migration input exceeds 10 MiB");
    JsonNode input = mapper.readTree(Files.readAllBytes(inputPath));
    if (!args[3].matches("[a-f0-9]{64}") || !args[3].equals(input.path("source").path("sha256").asText()))
      throw new IllegalArgumentException("Source SHA256 does not match explicit migration target");
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setDriverClassName("com.mysql.cj.jdbc.Driver");
    source.setUrl("jdbc:mysql://" + host + ":" + port + "/" + database
        + "?tinyInt1isBit=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false");
    source.setUsername(env("CRM_DB_USER", "emiecrm"));
    source.setPassword(env("CRM_DB_PASSWORD", ""));
    CrmRepository db = new CrmRepository(new JdbcTemplate(source), mapper);
    BusinessService business = new BusinessService(db, new AssetService(db));
    LegacyCustomerMigrationService service = new LegacyCustomerMigrationService(db, business, mapper);
    Actor actor = new Actor("旧系统客户迁移（运维）", "迁移运维", Set.of("business.read", "business.write", "payment.write", "system.manage"));
    TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setTimeout(120);
    transaction.setReadOnly(args[0].equals("preview"));
    JsonNode result = transaction.execute(status -> args[0].equals("preview")
        ? service.preview(input, actor) : service.commit(input, actor));
    Path report = inputPath.resolveSibling(inputPath.getFileName() + "." + args[0] + ".json");
    if (!Files.exists(report)) Files.createFile(report, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.setPosixFilePermissions(report, PosixFilePermissions.fromString("rw-------"));
    Files.writeString(report, mapper.writeValueAsString(result));
    System.out.println(mapper.writeValueAsString(result.path("summary")));
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
