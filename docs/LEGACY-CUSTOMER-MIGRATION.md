# 旧飞书 CRM 客户迁移

这是运维执行的历史资料迁移，不是聚水潭客户同步。旧主键、客户编号、多维表格记录 ID 保留在 `legacySource`，不会写入聚水潭 `externalLinks`。原 Excel 不进入 Git 或公开报告。

客户名称精确匹配且唯一时，关联现有 CRM 客户，仅追加来源与期初余额，保留当前负责人、联系方式等资料。名称不存在才新增；任何歧义、重复旧主键、金额不符、业务版本冲突均须处理后重新预览。已迁入的同一旧 ID 重跑会跳过，不重复开户或追加余额。源表缺失的资料保持为空，编辑历史客户时允许保留其原本缺失的资料；新客户仍遵守原有必填规则。

旧表已由用户确认的预收款作为 `openingBalance`，当前可用余额按“期初余额＋迁入后预收款－核销”计算。期初不会生成假订单或收款流水，也不会计入迁入当天新增收款。普通业务保存不能新增、修改或移除期初金额及来源依据。原表空白金额与明确的零分别保留；财务确认必须在执行前取得。

先保存数据库备份及当前状态 JSON，再准备私有输入（该命令不连接数据库）：

```sh
python3 scripts/prepare-legacy-customers.py /private/source.xlsx \
  --state /private/current-state.json --out /private/migration.json \
  --confirmed-opening-balances
```

此脚本按实际 XLSX 单元格解析，不依赖旧导出文件错误的 `dimension=A1`；按文本保留前导零、旧 ID 和手机，不执行公式。输出文件权限 `0600`，拒绝覆盖已有文件。

在包含当前代码的 CRM 镜像内，沿用该容器数据库环境执行 CLI：

```sh
java -Dloader.main=com.emie.crm.tools.LegacyCustomerMigration \
  -cp /app/crm.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  preview /private/migration.json emiecrm_prod SOURCE_SHA256
```

核对预览报告的新增、关联、跳过、错误及金额后，将 `preview` 改为 `commit`。必须明确传入目标数据库名和原始 Excel SHA256；提交还会检查输入中的业务版本及每行决策。CLI 使用专用运维审计身份和数据库事务，直接复用业务保存及资金保护，不启动 HTTP、飞书连接、初始化或聚水潭后台任务。报告写在输入文件旁边，包含客户资料，必须保密保存。

提交后核对客户数、全部旧 ID、期初总额，以及原有订单、商品和收款流水的指纹。生产中不得创建测试核销；核销和超额拒绝验证应在隔离的 `_test` 数据库进行。数据回退须基于本次迁移记录，禁止用旧全库备份覆盖迁入后的新业务。
