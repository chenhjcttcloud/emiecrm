import {test} from 'node:test';
import assert from 'node:assert/strict';
import {activeBusinessOrders, approvalDetails, approvalDiscount, approvalDraftValues, approvalInstanceUrl, approvalLocksOrder, approvalSettings, approvalStatus, approvalTime,
  canCancelOrder, canDeleteOrder, canSubmitApproval, isApprovalOrder, isLegacyApprovedSettlement, isSalesApprovalOrder, isSettlementApprovalOrder, orderExecutionView, recipientAddressText, submissionProblem, visibleBusinessOrders} from '../src/feishuApprovalModel.js';

const order = {id: '2026-11-00000001', type: '销售订单', status: '审批中'};
const configured = {required: true, salesEnabled: true, salesCanSubmit: true};

test('ordinary order types require Feishu even when the obsolete opt-out is supplied', () => {
  for (const type of ['销售订单','海外提货单','预收款订单']) {
    const ordinary = {...order,type};
    const disabled = {required:false,enabled:false,salesEnabled:false,prepaymentEnabled:false};
    assert.equal(approvalSettings(disabled,ordinary).required,true);
    assert.match(submissionProblem(disabled,ordinary),/审批模板/);
    assert.equal(canSubmitApproval(ordinary,null,disabled),false);
    assert.equal(approvalStatus(ordinary,null,disabled),'飞书审批未接通');
  }
  assert.match(submissionProblem({required: true, salesEnabled: false}, order), /审批模板/);
  assert.equal(canSubmitApproval(order, null, {required: true, salesEnabled: false}), false);
  assert.equal(approvalStatus(order, null, {required: true, salesEnabled: false}), '飞书审批未接通');
});

test('a configured template without application permission or an identity cannot submit', () => {
  const blocked = {...configured, salesCanSubmit: false, salesMessage: '飞书应用尚未获得审批接口权限'};
  assert.equal(canSubmitApproval(order, null, blocked), false);
  assert.match(submissionProblem(blocked, order), /审批接口权限/);
});

test('uncertain and active submissions stay locked even when client configuration is unavailable', () => {
  for (const status of ['QUEUED', 'SUBMITTING', 'PENDING', 'UNKNOWN', 'APPROVED']) {
    const record = {status, retryVersion: 'stale-retry'};
    assert.equal(approvalLocksOrder(record), true);
    assert.equal(canSubmitApproval(order, record, configured), false);
  }
});

test('resubmission requires a server issued version and respects the order status', () => {
  assert.equal(canSubmitApproval(order, null, configured), true);
  for (const status of ['REJECTED', 'CANCELED', 'DELETED', 'FAILED']) {
    assert.equal(canSubmitApproval(order, {status}, configured), false);
    assert.equal(canSubmitApproval(order, {status, retryVersion: 'last-attempt'}, configured), true);
  }
  assert.equal(canSubmitApproval({...order, status: '已批准'}, null, configured), false);
});

test('prepayments require their own template and settlement supplements use sales approval', () => {
  const deposit = {...order, type: '预收款订单'};
  assert.equal(approvalSettings(configured, deposit).enabled, false);
  assert.equal(canSubmitApproval(deposit, null, configured), false);
  const overseasPickup = {...order, type: '海外提货单'};
  assert.equal(canSubmitApproval(overseasPickup, null, configured), true);
  assert.deepEqual(approvalSettings(configured, overseasPickup), approvalSettings(configured, order));
  const settlement={...order,source:'jushuitan-settlement'};
  assert.equal(isSettlementApprovalOrder(settlement),true);
  assert.equal(isApprovalOrder(settlement),true);
  assert.equal(isSalesApprovalOrder(settlement),false);
  assert.deepEqual(approvalSettings(configured,settlement),approvalSettings(configured,order));
  assert.equal(canSubmitApproval(settlement,null,configured),true);
  assert.equal(approvalStatus(settlement,null,configured),'待发起飞书审批');
  assert.equal(approvalStatus(settlement,{status:'PENDING'},configured),'飞书审批中');
  assert.equal(canCancelOrder(settlement,null,null),false);
  assert.equal(canDeleteOrder({...settlement,status:'草稿'},null,null),false);
});

test('settlement supplements require Feishu permissions and versioned retries without ordinary shipping inputs', () => {
  const settlement={...order,source:'jushuitan-settlement',items:[],approvalDraft:{warehouseId:'42'}};
  const fullConfig={...configured,salesRecipientRequired:true,salesDiscountRequired:true};
  assert.deepEqual(approvalDetails(fullConfig,settlement,new Map()),{});
  assert.deepEqual(approvalDraftValues(fullConfig,settlement,new Map()),{});
  const disabled={salesEnabled:false,salesCanSubmit:false};
  assert.equal(approvalSettings(disabled,settlement).required,true);
  assert.equal(canSubmitApproval(settlement,null,disabled),false);
  assert.match(submissionProblem(disabled,settlement),/审批模板/);
  assert.doesNotMatch(submissionProblem(disabled,settlement),/保存草稿/);
  for(const status of ['REJECTED','CANCELED','DELETED','FAILED']){
    const draft={...settlement,status:'草稿'};
    assert.equal(canSubmitApproval(draft,{status},configured),false);
    assert.equal(canSubmitApproval(draft,{status,retryVersion:'previous-request'},configured),true);
  }
  for(const status of ['QUEUED','SUBMITTING','PENDING','UNKNOWN','APPROVED'])
    assert.equal(canSubmitApproval(settlement,{status,retryVersion:'stale-request'},configured),false);
  for(const invalid of [{status:'已批准'},{status:'已取消'},{deletedAt:'2026-11-24'}])
    assert.equal(canSubmitApproval({...settlement,...invalid},null,configured),false);
});

test('only approved settlement supplements without an approval record keep the legacy confirmation notice', () => {
  const settlement={...order,source:'jushuitan-settlement',status:'已批准'};
  assert.equal(isLegacyApprovedSettlement(settlement,null),true);
  assert.equal(isLegacyApprovedSettlement(settlement,undefined),true);
  for(const status of ['草稿','审批中','已取消'])
    assert.equal(isLegacyApprovedSettlement({...settlement,status},null),false);
  assert.equal(isLegacyApprovedSettlement({...settlement,source:undefined},null),false);
  assert.equal(isLegacyApprovedSettlement(settlement,{status:'APPROVED'}),false);
  assert.equal(isLegacyApprovedSettlement(null,null),false);
});

test('status loading failures are visible and do not hide a recorded Feishu outcome', () => {
  assert.equal(approvalStatus(order, null, configured, 'network'), '审批状态加载失败');
  assert.equal(approvalStatus(order, {status: 'REJECTED'}, configured, 'network'), '飞书审批已驳回');
  assert.match(submissionProblem(configured, order, 'network'), /重试/);
});

test('Feishu links only accept instance IDs and timestamps render in Shanghai', () => {
  assert.equal(approvalInstanceUrl('bad&url=other'), null);
  assert.equal(approvalInstanceUrl(undefined), null);
  const link = new URL(approvalInstanceUrl('test-instance'));
  assert.equal(link.origin + link.pathname, 'https://applink.feishu.cn/client/mini_program/open');
  assert.equal(link.searchParams.get('appId'), 'cli_9cb844403dbb9108');
  assert.equal(link.searchParams.get('path'), 'pc/pages/in-process/index?instanceId=test-instance');
  assert.equal(approvalTime('2026-11-23T00:00:00Z'), '2026-11-23 08:00:00');
  assert.equal(approvalTime(Date.parse('2026-11-23T00:00:00Z')), '2026-11-23 08:00:00');
  assert.equal(approvalTime(Date.parse('2026-11-23T00:00:00Z') / 1000), '2026-11-23 08:00:00');
  assert.equal(approvalTime(0), '');
  assert.equal(approvalTime('bad'), '');
});

test('prepayment requests keep the date and omit the server-managed receipt category', () => {
  const config = {...configured, prepaymentReceiptForm: true, prepaymentReceiptCategories: ['销售收入', '其他收入']};
  const deposit = {...order, type: '预收款订单'};
  const fields = values => new Map(Object.entries(values));
  assert.throws(() => approvalDetails(config, deposit, fields({})), /发生日期/);
  assert.throws(() => approvalDetails(config, deposit, fields({receiptDate: '2026-02-30', receiptCategory: '销售收入'})), /发生日期/);
  assert.deepEqual(approvalDetails(config, deposit, fields({receiptDate: '2026-11-23'})), {receiptDate: '2026-11-23'});
  assert.deepEqual(approvalDetails(config, deposit, fields({receiptDate: '2026-11-23', receiptCategory: '销售收入'})),
    {receiptDate: '2026-11-23'});
  const historical={...deposit,approvalDraft:{receiptCategory:'历史原始类别'}};
  const saved=approvalDraftValues(config,historical,fields({receiptDate:'2026-11-23'}));
  assert.deepEqual(saved,{receiptDate:'2026-11-23'});
  assert.equal(historical.approvalDraft.receiptCategory,'历史原始类别');
  assert.deepEqual(approvalDetails(config, order, fields({})), {});
  assert.deepEqual(approvalDetails(configured, deposit, fields({})), {});
});

test('explicit prepayment rows determine the latest draft date and complete frozen approval input', () => {
  const config = {...configured,prepaymentReceiptForm:true};
  const paymentDetails = [
    {lineNo:8,method:'现金',amount:'0.01',receiptDate:'2026-11-23'},
    {lineNo:8,method:'银行转账',amount:'1.99',receiptDate:'2026-11-25'},
  ];
  const deposit = {...order,type:'预收款订单',total:2,method:'组合收款',paymentDetails,
    approvalDraft:{receiptDate:'2026-01-01',receiptCategory:'历史类别'}};
  const before = structuredClone(deposit);
  const fields = new Map([['receiptDate','2026-01-02'],['receiptCategory','伪造类别']]);
  assert.deepEqual(approvalDraftValues(config,deposit,fields),{receiptDate:'2026-11-25'});
  assert.deepEqual(approvalDetails(config,deposit,fields),{
    receiptDate:'2026-11-25',paymentDetails:[
      {lineNo:1,method:'现金',amount:0.01,receiptDate:'2026-11-23'},
      {lineNo:2,method:'银行转账',amount:1.99,receiptDate:'2026-11-25'},
    ],
  });
  assert.deepEqual(deposit,before);
  assert.deepEqual(approvalDetails(configured,deposit,fields),approvalDetails(config,deposit,fields));
});

test('explicit prepayment rows also accompany generic or unavailable template configurations while legacy scalar stays unchanged', () => {
  const paymentDetails=[{lineNo:1,method:'现金',amount:20,receiptDate:'2026-11-25'}];
  const legacy={...order,type:'预收款订单',total:20,method:'现金'};
  const deposit={...legacy,paymentDetails};
  const fields=new Map([['receiptDate','2026-01-01']]);
  for(const config of [configured,{prepaymentReceiptForm:false},undefined,null]) {
    assert.deepEqual(approvalDraftValues(config,deposit,fields),{receiptDate:'2026-11-25'});
    assert.deepEqual(approvalDetails(config,deposit,fields),{receiptDate:'2026-11-25',paymentDetails});
    assert.deepEqual(approvalDraftValues(config,legacy,fields),{});
    assert.deepEqual(approvalDetails(config,legacy,fields),{});
    const hidden=new Map([...fields,['prepaymentPaymentDetails',JSON.stringify(paymentDetails)]]);
    assert.deepEqual(approvalDetails(config,legacy,hidden),{receiptDate:'2026-11-25',paymentDetails});
  }
});

test('editable prepayment form rows take precedence over stale controlled rows without mutating a legacy order', () => {
  const config = {...configured,prepaymentReceiptForm:true};
  const legacy = {...order,type:'预收款订单',method:'旧方式',total:100,
    approvalDraft:{receiptDate:'2026-11-20'}};
  const before = structuredClone(legacy);
  const rows = [{method:'现金',amount:'20.00',receiptDate:'2026-11-24'}];
  const fields = new Map([['prepaymentPaymentDetails',JSON.stringify(rows)],['receiptDate','2026-01-01']]);
  const expected = {receiptDate:'2026-11-24',paymentDetails:[{lineNo:1,method:'现金',amount:20,receiptDate:'2026-11-24'}]};
  assert.deepEqual(approvalDetails(config,legacy,fields),expected);
  assert.deepEqual(approvalDraftValues(config,legacy,fields),{receiptDate:'2026-11-24'});
  assert.deepEqual(approvalDetails(config,{...legacy,paymentDetails:[{method:'无效'}]},fields),expected);
  assert.deepEqual(legacy,before);
  assert.equal(Object.hasOwn(legacy,'paymentDetails'),false);
});

test('malformed or invalid explicit prepayment rows cannot silently fall back to a legacy receipt date', () => {
  const config = {...configured,prepaymentReceiptForm:true};
  const legacy = {...order,type:'预收款订单',method:'现金',total:100};
  const fields = new Map([['receiptDate','2026-11-25']]);
  for (const paymentDetails of [undefined,null,{},'bad',[],[{method:'预收款核销',amount:1,receiptDate:'2026-11-25'}],
    [{method:'现金',amount:1,receiptDate:'2026-02-30'}]]) {
    assert.throws(()=>approvalDetails(config,{...legacy,paymentDetails},fields),/明细|核销|日期/);
    assert.throws(()=>approvalDraftValues(config,{...legacy,paymentDetails},fields),/明细|核销|日期/);
  }
  for(const raw of ['', 'not-json', 'null', '{}', '[]']) {
    const invalid = new Map([...fields,['prepaymentPaymentDetails',raw]]);
    assert.throws(()=>approvalDetails(config,legacy,invalid),/明细/);
  }
});

test('real sales template recipient fields are explicit, trimmed and retained in draft snapshots', () => {
  const config = {...configured, salesRecipientRequired: true, salesDiscountRequired: true};
  const sales = {...order, items: [{product: 'p1', qty: '2', price: '10'}, {product: 'p2', qty: '1', price: '15'}]};
  const fields = new Map([['recipientName', ' 张三 '], ['recipientPhone', ' +86 13800000000 '],
    ['recipientAddress', ' 广东省深圳市某路1号 '], ['approvalDiscount-0', ''], ['approvalDiscount-1', '0.000001']]);
  assert.deepEqual(approvalDetails(config, sales, fields), {recipientName: '张三', recipientPhone: '+86 13800000000',
    recipientAddress: '广东省深圳市某路1号', discounts: ['0', '0.000001']});
  assert.deepEqual(approvalDetails(config, {...sales, type: '海外提货单'}, fields), approvalDetails(config, sales, fields));
  assert.equal(approvalDraftValues(config, sales, fields).recipientName, ' 张三 ');
  fields.set('recipientAddress', ' ');
  assert.throws(() => approvalDetails(config, sales, fields), /收货地址/);
  assert.equal(approvalDraftValues(config, sales, fields).recipientAddress, ' ');
  fields.set('recipientAddress', 'a'.repeat(501));
  assert.throws(() => approvalDetails(config, sales, fields), /最多500/);
  assert.deepEqual(sales.items.map(item => item.price), ['10', '15']);
});

test('display omits existing county grouping labels without rewriting structured or legacy address fields', () => {
  for (const grouping of ['省直辖县', '省直辖县级行政区划', '省直辖行政单位']) {
    const draft = {recipientProvince: '湖北省', recipientCity: grouping, recipientDistrict: '仙桃市', recipientAddress: '测试路10号'};
    const before = structuredClone(draft);
    assert.equal(recipientAddressText(draft), '湖北省仙桃市测试路10号');
    assert.deepEqual(draft, before);
  }
  const legacy = {recipientAddress: '湖北省省直辖县仙桃市测试路10号'};
  assert.equal(recipientAddressText(legacy), legacy.recipientAddress);
  assert.deepEqual(Object.keys(legacy), ['recipientAddress']);
  assert.equal(recipientAddressText({recipientProvince: '广东省', recipientCity: '东莞市', recipientDistrict: '松山湖', recipientAddress: '测试路10号'}), '广东省东莞市松山湖测试路10号');
  assert.equal(recipientAddressText({recipientProvince: '北京市', recipientCity: '北京市', recipientDistrict: '朝阳区', recipientAddress: '测试路10号'}), '北京市朝阳区测试路10号');
});

test('approval discounts validate exact decimal limits without changing CRM prices', () => {
  for (const value of ['', '0', '0.000001', '999999999999.990000', '1e-6', '.5'])
    assert.equal(approvalDiscount(value), value || '0');
  for (const value of ['-0.01', '0.0000001', '999999999999.990001', '1000000000000', '1e1000', 'abc'])
    assert.throws(() => approvalDiscount(value), /商品折扣/);
});

test('order cancellation respects server ownership checks, money, type and shipment links', () => {
  const draft = {...order, status: '草稿', paid: 0};
  assert.equal(canCancelOrder(draft), true);
  assert.equal(canCancelOrder({...draft, type: '预收款订单'}), true);
  assert.equal(canCancelOrder({...draft, type: '海外提货单'}), true);
  assert.equal(canCancelOrder(draft, {status: 'PENDING', canCancel: false}), false);
  assert.equal(canCancelOrder(draft, {status: 'PENDING', canCancel: true}), true);
  assert.equal(canCancelOrder(draft, null, {status: 'queued'}), false);
  assert.equal(canCancelOrder(draft, {canCancel: true, creditPush: {soId: 'CRM-stable', status: 'QUEUED'}}), false);
  for (const invalid of [{paid: '0.01'}, {status: '已批准'}, {status: '已取消'}, {deletedAt: '2026-11-23'}, {source: 'jushuitan-settlement'}])
    assert.equal(canCancelOrder({...draft, ...invalid}), false);
});

test('soft deletion never removes an active external approval and respects server canDelete', () => {
  const draft = {...order, status: '草稿', paid: 0};
  assert.equal(canDeleteOrder(draft), true);
  assert.equal(canDeleteOrder({...draft, type: '海外提货单'}), true);
  assert.equal(canDeleteOrder({...draft, type: '预收款订单'}), false);
  assert.equal(canDeleteOrder(draft, {status: 'PENDING', canDelete: false}), false);
  assert.equal(canDeleteOrder({...draft, status: '已取消'}, {status: 'CANCELED', canDelete: true}), true);
  assert.equal(canDeleteOrder({...draft, paid: 1}, {canDelete: true}), false);
  assert.equal(canDeleteOrder({...draft, deletedAt: '2026-11-23'}, {canDelete: true}), false);
  assert.equal(canDeleteOrder(draft, {canDelete: true}, {status: 'queued'}), false);
  assert.equal(canDeleteOrder(draft, {canDelete: true, creditPush: {soId: 'CRM-stable', status: 'QUEUED'}}), false);
});

test('canceled and deleted orders cannot be resubmitted or counted as active amounts', () => {
  const canceled = {...order, id: 'canceled', status: '已取消', total: 100};
  const deleted = {...order, id: 'deleted', deletedAt: '2026-11-23', total: 200};
  const active = {...order, id: 'active', total: 50};
  const all = [canceled, deleted, active];
  assert.deepEqual(visibleBusinessOrders(all).map(row => row.id), ['canceled', 'active']);
  assert.equal(activeBusinessOrders(all).reduce((sum, row) => sum + row.total, 0), 50);
  assert.equal(approvalStatus(canceled, {status: 'CANCELED'}, configured), '已取消');
  for (const row of [canceled, deleted]) {
    assert.equal(canSubmitApproval(row, {status: 'REJECTED', retryVersion: 'previous'}, configured), false);
  }
});

test('order details use current execution items together with current execution amounts', () => {
  const original = {...order, total: '100.00', paid: '10.00', items: [{product: 'old', qty: '1', price: '100'}]};
  const changed = {total: '80.00', paid: '20.00', items: [{product: 'new', qty: '2', price: '40'}]};
  const view = orderExecutionView(original, {effectiveOrder: changed});
  assert.equal(view.total, '80.00');
  assert.equal(view.paid, '20.00');
  assert.equal(view.unpaid, 60);
  assert.equal(view.executionItems, true);
  assert.deepEqual(view.items, changed.items);
  assert.deepEqual(original.items, [{product: 'old', qty: '1', price: '100'}]);
  assert.deepEqual(orderExecutionView(original, {effectiveOrder: {total: '80.00'}}).items, original.items);
  assert.equal(orderExecutionView({...original, status: '已取消'}, {effectiveOrder: changed}).unpaid, 0);
});
