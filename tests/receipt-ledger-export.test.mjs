import test from 'node:test';
import assert from 'node:assert/strict';
import {receiptLedgerCsv} from '../src/receiptLedgerExport.js';

test('receipt CSV shows business numbers while retaining original receipt and order relationships',()=>{
  const orders=[{id:'JS-internal-uuid',orderNumber:'2026-11-00000001',source:'jushuitan-settlement'},
    {id:'2026-11-00000002'}];
  const rows=[{id:'receipt-1',orderId:orders[0].id,customer:'客户,名称',amount:20},
    {id:'receipt-2',orderId:orders[1].id,amount:30},
    {id:'receipt-3',orderId:'unmapped-history-order',amount:40}];
  const before=structuredClone({orders,rows});
  const csv=receiptLedgerCsv(rows,orders);
  assert.ok(csv.startsWith('\uFEFF"订单编号"'));
  assert.match(csv,/\r\n"2026-11-00000001","客户,名称"/);
  assert.match(csv,/\r\n"2026-11-00000002"/);
  assert.match(csv,/\r\n"unmapped-history-order"/);
  assert.doesNotMatch(csv,/JS-internal-uuid/);
  assert.deepEqual({orders,rows},before);
});

test('receipt CSV uses projected business numbers when the associated order is outside the current state',()=>{
  const csv=receiptLedgerCsv([{orderId:'JS-internal-uuid',orderNumber:'2026-11-00000003',amount:10}],[]);
  assert.match(csv,/\r\n"2026-11-00000003"/);
  assert.doesNotMatch(csv,/JS-internal-uuid/);
});

test('historical receipt order fields map to the current business number',()=>{
  const csv=receiptLedgerCsv([{order:'JS-internal-uuid',amount:10}],
    [{id:'JS-internal-uuid',orderNumber:'2026-11-00000004'}]);
  assert.match(csv,/\r\n"2026-11-00000004"/);
});

const csvFields=line=>(line.match(/"(?:""|[^"])*"/g)||[]).map(cell=>cell.slice(1,-1).replaceAll('""','"'));

test('split receipt CSV keeps every real method and date and appends batch trace after the original columns',()=>{
  const rows=[
    {id:'r1',orderId:'o1',amount:150,method:'银行转账',receivedOn:'2026-11-17',approvalId:'receipt-batch-1',paymentLineNo:1},
    {id:'r2',orderId:'o1',amount:50,method:'自定义配置账户',receivedOn:'2026-11-18',approvalId:'receipt-batch-1',paymentLineNo:2},
    {id:'r3',orderId:'o2',amount:100,method:'自定义配置账户',receivedOn:'2026-11-18',approvalId:'receipt-batch-1',paymentLineNo:2},
  ];
  const before=structuredClone(rows);
  const [header,...lines]=receiptLedgerCsv(rows).slice(1).split('\r\n').map(csvFields);
  assert.deepEqual(header.slice(0,15),['订单编号','客户名称','签单人','订单当前回款状态','流水类型','收款方式','收款金额','税率（原始记录）','税额','订单日期','实际到账日期','登记时间','操作人','产品资料来源','产品明细']);
  assert.deepEqual(header.slice(15),['收款批次','收款明细序号']);
  assert.deepEqual(lines.map(line=>[line[0],line[5],line[6],line[10],line[15],line[16]]),[
    ['o1','银行转账','150','2026-11-17','receipt-batch-1','1'],
    ['o1','自定义配置账户','50','2026-11-18','receipt-batch-1','2'],
    ['o2','自定义配置账户','100','2026-11-18','receipt-batch-1','2'],
  ]);
  assert.doesNotMatch(receiptLedgerCsv(rows),/组合收款/);
  assert.deepEqual(rows,before);
});

test('legacy receipt CSV leaves new trace columns empty and preserves signed amounts and missing dates',()=>{
  const [,line]=receiptLedgerCsv([{orderId:'legacy',method:'旧退款渠道',amount:-20}]).slice(1).split('\r\n').map(csvFields);
  assert.equal(line.length,17);
  assert.equal(line[5],'旧退款渠道');assert.equal(line[6],'-20');assert.equal(line[10],'');
  assert.deepEqual(line.slice(15),['','']);
});
