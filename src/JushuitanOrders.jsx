import JushuitanExternalDifference from './JushuitanExternalDifference';
import JushuitanAuthorization from './JushuitanAuthorization';
import React,{useState,useEffect} from 'react';
import {orderTotal} from './orderMoney';
import {orderNumber} from './orderNumber';
import {jstNativeStatusLabel,shippingWarehouseId} from './feishuApprovalModel';

const labels={SUBMITTING:'提交中',UNKNOWN:'待核对',FAILED:'提交失败',ACCEPTED:'待发货',PARTIAL:'部分发货',SHIPPED:'已发货',EXCEPTION:'聚水潭异常',CANCELLED:'已取消'};
export const shipmentLabel=record=>record?.externalDifference?'聚水潭变更待核对':jstNativeStatusLabel(record?.nativeStatus)||(record?(record.creditFulfillment&&record.status==='ACCEPTED'?'聚水潭已接单 / 待核对审核进度':labels[record.status]||'待核对'):'未提交');
export function useJushuitan(api,revision){
 const[records,setRecords]=useState({});const[config,setConfig]=useState(null);const[error,setError]=useState('');
 useEffect(()=>{if(revision==null)return;let active=true;const load=()=>Promise.all([api('jst/config'),api('jst/shipments')]).then(([c,rows])=>{if(active){setConfig(c);setRecords(Object.fromEntries(rows.map(r=>[r.orderId,r])));setError('')}}).catch(e=>{if(active)setError(e.message)});load();const timer=setInterval(load,60000);return()=>{active=false;clearInterval(timer)}},[revision]);
 return {records,config,error,update:record=>setRecords(rows=>({...rows,[record.orderId]:record}))};
}
export default function ShipmentDialog({order,db,api,record,config,onUpdate,onClose,DialogLayer,canWrite,canSettle,paymentMethods=[]}){
 const[replacementWarehouse,setReplacementWarehouse]=useState('');
 const[lines,setLines]=useState([]);const[settlement,setSettlement]=useState({amount:'',method:'',reference:''});const[changeKind,setChangeKind]=useState('');const[reason,setReason]=useState('');const[remark,setRemark]=useState('');const[changeVersion,setChangeVersion]=useState('');const[form,setForm]=useState(null);const[shops,setShops]=useState([]);const[warehouses,setWarehouses]=useState([]);const[busy,setBusy]=useState(false);const[error,setError]=useState('');const[stocks,setStocks]=useState({});
 useEffect(()=>{if(!order){setForm(null);return}let active=true;setError('');setStocks({});setSettlement({amount:'',method:'',reference:''});setChangeKind('');setReason('');setRemark('');
 const shippingOrder=record?.effectiveOrder||order;
 const c=db.customers.find(c=>c.id===order.customerId)||{};const prior=record?.request;
 setForm({shopId:prior?.shop_id||'',warehouseId:shippingWarehouseId(order,record),recipient:{name:prior?.receiver_name||c.contact||'',phone:prior?.receiver_mobile||c.phone||'',province:prior?.receiver_state||'',city:prior?.receiver_city||'',district:prior?.receiver_district||'',address:prior?.receiver_address||''},items:(shippingOrder.items||[]).map((line,i)=>({skuId:prior?.items?.[i]?.sku_id||db.products.find(p=>p.id===line.product)?.jstSku||''}))});
 if(config?.configured&&canWrite)Promise.all([api('jst/shops'),api('jst/warehouses')]).then(([rows,ws])=>{if(active){setShops(rows);setWarehouses(ws)}}).catch(e=>{if(active)setError(e.message)});
 return()=>{active=false};},[order?.id,record?.soId,config?.configured,canWrite]);
 useEffect(()=>{if(record?.externalDifference)setChangeKind('')},[record?.externalDifference?.id]);
 useEffect(()=>{setSettlement({amount:'',method:'',reference:''})},[order?.id,record?.amendment?.id]);
 useEffect(()=>{
  if(!record?.request||changeKind)return;
  const r=record.request;
  setForm(f=>f?{...f,shopId:r.shop_id||'',warehouseId:r.wms_co_id||'',items:(r.items||[]).map(line=>({skuId:line.sku_id||''})),recipient:{name:r.receiver_name||'',phone:r.receiver_mobile||'',province:r.receiver_state||'',city:r.receiver_city||'',district:r.receiver_district||'',address:r.receiver_address||''}}:f);
 },[record?.request,changeKind]);
 if(!order||!form)return null;
 const editable=canWrite&&(!record||(record.status==='FAILED'&&!record.upstreamAttempted));
 const canSubmit=!record?.creditFulfillment&&canWrite&&config?.configured&&config?.uploadsEnabled&&!record?.externalDifference&&(!record||record.status==='FAILED');
 const shippingOrder=record?.effectiveOrder||order;
 const amendment=record?.amendment;const activeAmendment=amendment&&!['COMPLETE','ABANDONED'].includes(amendment.status);
 const amendmentLabels={DRAFT:'待取消原单',WAIT_CANCEL:'原单取消待核对',AWAITING_FUNDS:'待登记补收或退款',READY:'待推送新单',SUBMITTING:'新单提交中',UNKNOWN:'新单结果待核对',FAILED:'新单提交失败',COMPLETE:'已生效',ABANDONED:'已放弃'};
 const unresolved=['PENDING','UNKNOWN'].includes(record?.change?.status);
 const canManage=canWrite&&config?.configured&&record?.oId>0
  &&!unresolved&&!activeAmendment&&!record?.externalDifference;
 const canChange=canManage&&config?.changesEnabled&&['ACCEPTED','EXCEPTION'].includes(record?.status);
 const canReplaceManually=!record?.creditFulfillment&&canManage&&config?.uploadsEnabled&&['ACCEPTED','EXCEPTION','CANCELLED'].includes(record?.status);
 const manualCancellation=changeKind==='CORE_MANUAL';
 const replacing=changeKind==='CORE'||manualCancellation;
 const selectedReplacementWarehouse=warehouses.some(warehouse=>String(warehouse.id)===String(replacementWarehouse))
  ? replacementWarehouse : '';
 const changeTitles={CORE:'关联变更单',CORE_MANUAL:'核对手工取消并重推',ADDRESS:'变更收件信息',REMARK:'变更发货备注',CANCEL:'取消发货订单'};

 function beginChange(kind){
  setChangeKind(kind);
  setChangeVersion(record.editVersion||record.updatedAt);
  setReason('');
  setError('');
  const request=record.request;
  if(kind==='CORE'||kind==='CORE_MANUAL'){
   setLines(shippingOrder.items.map((line,index)=>({...line,skuId:request.items?.[index]?.sku_id||''})));
   setReplacementWarehouse(request.wms_co_id||'');
  }
  setForm(current=>({...current,recipient:{
   name:request.receiver_name||'',phone:request.receiver_mobile||'',province:request.receiver_state||'',
   city:request.receiver_city||'',district:request.receiver_district||'',address:request.receiver_address||''
  }}));
 }

 async function amend(event){
  event.preventDefault();
  if(busy)return;
  if(!manualCancellation&&!window.confirm('提交后将先取消旧发货单，差额补收或退款登记完成后再推送新单。确认继续？'))return;
  setBusy(true);
  setError('');
  const endpoint='jst/orders/'+encodeURIComponent(order.id);
  try{
   const updated=await api(endpoint+'/amend',{
    version:changeVersion,reason,manualCancellation,warehouseId:Number(selectedReplacementWarehouse),
    items:lines.map(({product,qty,price})=>({product,qty:Number(qty),price:Number(price)})),
    mappings:lines.map(line=>({skuId:line.skuId}))
   });
   onUpdate(updated);
   setChangeKind('');
  }catch(error){
   setError(error.message);
   try{onUpdate(await api(endpoint+'/sync',{}))}catch{}
  }finally{
   setBusy(false);
  }
 }
 async function exportExternal(){if(busy)return;setBusy(true);setError('');try{const history=await api('jst/orders/'+encodeURIComponent(order.id)+'/external/history');const url=URL.createObjectURL(new Blob([JSON.stringify(history,null,2)],{type:'application/json;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=orderNumber(order).replace(/[^a-zA-Z0-9_-]/g,'_')+'-聚水潭变更记录.json';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function confirmExternal(body){if(busy)return;setBusy(true);setError('');try{onUpdate(await api('jst/orders/'+encodeURIComponent(order.id)+'/external/confirm',body))}catch(e){setError(e.message);try{onUpdate(await api('jst/orders/'+encodeURIComponent(order.id)+'/sync',{}))}catch{}}finally{setBusy(false)}}
 async function continueAmend(abandon=false){if(busy)return;setBusy(true);setError('');try{onUpdate(await api('jst/orders/'+encodeURIComponent(order.id)+'/amend/continue',{...settlement,abandon}));setSettlement({amount:'',method:'',reference:''})}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function change(e){e.preventDefault();if(busy)return;if(changeKind==='CANCEL'&&!window.confirm('确认取消聚水潭发货订单？取消后不会自动退款，也不会自动创建新发货单。'))return;setBusy(true);setError('');try{const r=await api('jst/orders/'+encodeURIComponent(order.id)+'/change',{kind:changeKind,version:changeVersion,reason,remark,recipient:form.recipient});onUpdate(r);setChangeKind('');if(r.change?.status!=='CONFIRMED')setError(r.change?.message||'变更待核对')}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function submit(e){e.preventDefault();if(busy)return;setError('');setBusy(true);try{const r=await api('jst/orders/'+encodeURIComponent(order.id)+'/submit',{...form,shopId:Number(form.shopId),warehouseId:Number(form.warehouseId)});onUpdate(r);if(['FAILED','UNKNOWN'].includes(r.status))setError(r.message)}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function sync(){setBusy(true);setError('');try{onUpdate(await api('jst/orders/'+encodeURIComponent(order.id)+'/sync',{}))}catch(e){setError(e.message)}finally{setBusy(false)}}
 async function stock(index){setBusy(true);setError('');try{const rows=await api('jst/inventory',{skuId:(form.items[index]?.skuId||record?.request?.items?.[index]?.sku_id||''),warehouseId:Number(form.warehouseId)});setStocks(s=>({...s,[index]:rows}))}catch(e){setError(e.message)}finally{setBusy(false)}}
 return <DialogLayer open={!!order} onClose={()=>{if(!busy)onClose()}} kind="drawer" label="提交发货与物流进度"><form className="drawer" onClick={e=>e.stopPropagation()} onSubmit={replacing?amend:changeKind?change:submit}><div className="modal-title"><h2>发货与物流进度</h2><button type="button" disabled={busy} onClick={onClose} aria-label="关闭">×</button></div><div className="modal-body jst-form">
 <p className="jst-environment">{config?.environment==='production'?'正式环境 · 提交后将进入聚水潭仓库流程':'测试环境 · 仅用于联调，禁止实际投递'}</p>
 <JushuitanAuthorization authorization={config?.authorization}/>
 <JushuitanExternalDifference order={order} record={record} busy={busy} canWrite={canWrite} canSettle={canSettle} paymentMethods={paymentMethods} onConfirm={confirmExternal} onExport={exportExternal}/>
 {config?.configured&&!config?.uploadsEnabled&&<p role="status">当前为只读连接，订单推送尚未开启。</p>}
 {!config?.configured&&<p role="alert">尚未配置云端服务，请联系管理员。</p>}
 <div className="read-field">CRM订单<p>{orderNumber(order)} · {order.customer}</p><small>原订单金额 ¥ {Number(order.total).toFixed(2)} · 原单已收 ¥ {Number(order.paid||0).toFixed(2)}{record?.effectiveOrder&&<> · 当前执行金额 ¥ {Number(shippingOrder.total).toFixed(2)}</>}</small></div>
 <p>{record?.creditFulfillment?'本单已按账期挂账（非实收）推送；ERP付款状态显示已付，CRM实收和应收仍按实际到账核算。可在此核对仓库、物流及收件信息，商品金额变更须重新核对审批。':'批准且全额收款的销售订单可提交。提交后可在此变更收件信息、发货备注或取消发货。商品、数量和金额通过关联变更单处理；原订单及收款记录保留。'}</p>
 {record&&<section className="jst-progress"><strong>{shipmentLabel(record)}</strong><p>{record.message}</p>{record.syncError&&<p role="alert">{record.syncError}（保留上次状态）</p>}<small>聚水潭订单号：{record.soId}<br/>内部单号：{record.oId||'待回传'}<br/>最后同步：{record.lastSyncedAt?new Date(record.lastSyncedAt).toLocaleString():'尚未同步'}</small></section>}
 {record?.change&&<section className="jst-progress" role="status"><strong>订单变更 · {{PENDING:'待核对',UNKNOWN:'结果待核对',CONFIRMED:'已生效',REJECTED:'已拒绝'}[record.change.status]||record.change.status}</strong><p>{record.change.message}</p><small>操作人：{record.change.operator} · 原因：{record.change.reason}</small></section>}
 {record&&!config?.changesEnabled&&<p>订单变更服务尚未启用，请联系管理员开通接口并启用同步服务。</p>}
 {(canChange||canReplaceManually)&&!changeKind&&<div className="management-actions">
  {!record?.creditFulfillment&&canChange&&config?.uploadsEnabled&&<button type="button" className="secondary" disabled={busy} onClick={()=>beginChange('CORE')}>变更商品与金额</button>}
  {canReplaceManually&&<button type="button" className="secondary" disabled={busy} onClick={()=>beginChange('CORE_MANUAL')}>已在聚水潭取消，修改并重推</button>}
  {canChange&&<>
   <button type="button" className="secondary" disabled={busy} onClick={()=>beginChange('ADDRESS')}>变更收件信息</button>
   <button type="button" className="secondary" disabled={busy} onClick={()=>beginChange('REMARK')}>变更发货备注</button>
   <button type="button" className="secondary" disabled={busy} onClick={()=>beginChange('CANCEL')}>取消发货订单</button>
  </>}
 </div>}
 {changeKind&&<section className="jst-progress"><h3>{changeTitles[changeKind]}</h3><p>提交前会核对聚水潭状态和出库记录；实际结果以同步核对为准。</p>{changeKind==='CANCEL'&&<p>仅取消聚水潭发货订单，CRM原订单和收款保留；退款及重新下单需另行处理。</p>}<label className="field"><span>变更原因 *</span><input required maxLength={replacing?160:200} disabled={busy} value={reason} onChange={e=>setReason(e.target.value)}/></label>{replacing&&<>
  <p>{manualCancellation?'请先在聚水潭取消旧单。CRM 核对旧单状态和出库记录后，方可推送新单。':'保留原单及原收款，先取消旧发货单，再按差额补收或退款。'}</p>
  <p>当前单金额 ¥ {Number(shippingOrder.total).toFixed(2)}</p>
  <label className="field">
   <span>新单发货仓库 *</span>
   <select required disabled={busy} value={selectedReplacementWarehouse} onChange={event=>setReplacementWarehouse(event.target.value)}>
    <option value="">请选择发货仓库</option>
    {warehouses.map(warehouse=><option key={warehouse.id} value={warehouse.id}>
     {warehouse.name}{warehouse.main?'（本仓）':''}（{warehouse.id}）
    </option>)}
   </select>
  </label>
  {lines.map((line,i)=><fieldset key={i} disabled={busy}><legend>商品 {i+1}</legend><label className="field"><span>商品 *</span><select required value={line.product} onChange={e=>{const p=db.products.find(p=>p.id===e.target.value);setLines(rows=>rows.map((r,n)=>n===i?{...r,product:e.target.value,price:p?.price||0,skuId:p?.jstSku||''}:r))}}><option value="">请选择商品</option>{db.products.filter(p=>p.source==='jushuitan'&&p.enabled&&!p.historicalOnly).map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label>{[['qty','数量'],['price','单价'],['skuId','聚水潭SKU']].map(([key,label])=><label className="field" key={key}><span>{label} *</span><input readOnly={key==='skuId'} required type={key==='skuId'?'text':'number'} min={key==='qty'?1:0} step={key==='qty'?1:'0.01'} maxLength={40} value={line[key]} onChange={e=>setLines(rows=>rows.map((r,n)=>n===i?{...r,[key]:e.target.value}:r))}/></label>)}<button type="button" className="link-btn" disabled={lines.length<=1} onClick={()=>setLines(rows=>rows.filter((_,n)=>n!==i))}>移除此商品</button></fieldset>)}<button type="button" className="secondary" disabled={busy} onClick={()=>setLines(rows=>[...rows,{product:'',qty:1,price:0,skuId:''}])}>添加商品</button><p>变更后金额 ¥ {orderTotal(lines)??'请检查明细'} · 差额 ¥ {orderTotal(lines)!==null?(orderTotal(lines)-Number(shippingOrder.total)).toFixed(2):'—'}</p></>}{changeKind==='REMARK'&&<label className="field"><span>新的发货备注（覆盖聚水潭卖家备注）</span><textarea maxLength={400} disabled={busy} value={remark} onChange={e=>setRemark(e.target.value)}/></label>}</section>}
 {amendment&&<section className="jst-progress"><h3>关联变更单 · {amendmentLabels[amendment.status]||amendment.status}</h3><small>{amendment.id}</small><p>{amendment.reason}</p><p>原金额 ¥ {Number(amendment.before.total).toFixed(2)} → 新金额 ¥ {Number(amendment.effectiveOrder.total).toFixed(2)}<br/>{Number(amendment.difference)>0?'需补收':Number(amendment.difference)<0?'需退款':'无差额'} ¥ {Math.abs(Number(amendment.difference)).toFixed(2)}</p><p>原聚水潭单号：{amendment.oldSoId}<br/>新聚水潭单号：{amendment.newSoId}<br/>新单发货仓库：{warehouses.find(warehouse=>warehouse.id===amendment.request?.wms_co_id)?.name||amendment.request?.wms_co_id||'—'}</p>
 {amendment.cancellationVerifiedAt&&<p>已核对聚水潭手工取消，核对时间 {new Date(amendment.cancellationVerifiedAt).toLocaleString()}</p>}{amendment.message&&<p>{amendment.message}</p>}{amendment.settlement&&<p>差额已登记：¥ {Number(amendment.settlement.amount).toFixed(2)} · {amendment.settlement.method} · 流水号 {amendment.settlement.reference}</p>}{activeAmendment&&canWrite&&<>{amendment.status==='AWAITING_FUNDS'&&!amendment.settlement&&<><p>请先在银行或支付渠道完成实际{Number(amendment.difference)>0?'补收':'退款'}，再登记下方流水。本操作不会划转资金。</p><label className="field"><span>实际{Number(amendment.difference)>0?'补收':'退款'}金额</span><input disabled={!canSettle||busy} type="number" min="0.01" step="0.01" value={settlement.amount} onChange={e=>setSettlement({...settlement,amount:e.target.value})}/></label><label className="field"><span>收退款方式</span><select disabled={!canSettle||busy} value={settlement.method} onChange={e=>setSettlement({...settlement,method:e.target.value})}><option value="">请选择</option>{paymentMethods.map(name=><option key={name}>{name}</option>)}</select></label><label className="field"><span>银行/支付流水号</span><input disabled={!canSettle||busy} maxLength={100} value={settlement.reference} onChange={e=>setSettlement({...settlement,reference:e.target.value})}/></label>{!canSettle&&<p>请有收款权限的同事登记差额。</p>}</>}<button className="primary" type="button" disabled={busy||(amendment.status==='AWAITING_FUNDS'&&!canSettle)} onClick={()=>continueAmend()}>{busy?'处理中…':amendment.status==='AWAITING_FUNDS'?'确认差额已完成并推送新单':amendment.status==='READY'?'推送新发货单':'核对并继续变更'}</button>{amendment.status==='DRAFT'&&<button className="secondary" type="button" disabled={busy||unresolved} onClick={()=>continueAmend(true)}>放弃未执行变更单</button>}</>}</section>}
 {record?.amendments?.length>0&&<details><summary>历史关联变更单（{record.amendments.length}）</summary>{record.amendments.map(a=><p key={a.id}>{a.id} · {a.reason}<br/>¥ {Number(a.before.total).toFixed(2)} → ¥ {Number(a.effectiveOrder.total).toFixed(2)} · 差额 ¥ {Number(a.difference).toFixed(2)}<br/>{a.oldSoId} → {a.newSoId}</p>)}</details>}
 <label className="field"><span>聚水潭店铺 *</span><select required disabled={!editable||busy} value={form.shopId} onChange={e=>setForm({...form,shopId:e.target.value})}><option value="">请选择店铺</option>{record?.request?.shop_id&&!shops.some(s=>String(s.id)===String(form.shopId))&&<option value={form.shopId}>店铺 {form.shopId}</option>}{shops.map(shop=><option key={shop.id} value={shop.id}>{shop.name}（{shop.id}）</option>)}</select></label>
 <label className="field"><span>发货仓库 *</span><select required disabled={!editable||busy} value={form.warehouseId} onChange={e=>{setForm({...form,warehouseId:e.target.value});setStocks({})}}><option value="">请手工选择仓库</option>{form.warehouseId&&!warehouses.some(w=>String(w.id)===String(form.warehouseId))&&<option value={form.warehouseId}>仓库 {form.warehouseId}</option>}{warehouses.map(w=><option key={w.id} value={w.id}>{w.name}{w.main?'（本仓）':''}（{w.id}）</option>)}</select></label>
 {record?.warehouseMismatch&&<p role="alert">聚水潭实际仓库与手工选择不一致，请先核对仓库再发货。</p>}
 {record?.actualWarehouseIds?.length>0&&<p>聚水潭实际仓库：{[...new Set(record.actualWarehouseIds)].map(id=>warehouses.find(w=>w.id===id)?.name||id).join('、')}</p>}
 <fieldset disabled={(!editable&&changeKind!=='ADDRESS')||busy} className="jst-recipient"><legend>收货信息</legend>{[['name','收件人'],['phone','联系电话'],['province','省'],['city','市'],['district','区/县'],['address','详细地址']].map(([key,label])=><label className="field" key={key}><span>{label} *</span><input required maxLength={key==='address'?200:50} value={form.recipient[key]} onChange={e=>setForm({...form,recipient:{...form.recipient,[key]:e.target.value}})}/></label>)}</fieldset>
 <h3>商品 SKU 对应</h3>{(shippingOrder.items||[]).map((line,index)=><section className="jst-sku" key={index}><p>{db.products.find(p=>p.id===line.product)?.name||line.product} · {line.qty} 件</p><label className="field"><span>聚水潭 SKU *</span><input required maxLength={40} readOnly disabled={!editable||busy} value={(form.items[index]?.skuId||record?.request?.items?.[index]?.sku_id||'')} onChange={e=>setForm({...form,items:form.items.map((item,i)=>i===index?{skuId:e.target.value}:item)})}/></label><button className="link-btn" type="button" disabled={busy||!config?.configured||!form.warehouseId||!(form.items[index]?.skuId||record?.request?.items?.[index]?.sku_id||'')} onClick={()=>stock(index)}>查询库存</button>{stocks[index]&&<div className="jst-stock">{stocks[index].length?stocks[index].map((row,i)=><p key={i}>实物 {row.qty??0} · 订单占用 {row.order_lock??0} · 仓库待发 {row.pick_lock??0}</p>):'未找到库存记录'}<small>所选仓库的聚水潭实时库存快照，CRM不维护或扣减库存。</small></div>}</section>)}
 {record?.shipments?.length>0&&<section><h3>出库与物流</h3>{record.shipments.map(s=><div className="jst-progress" key={s.id}><strong>{s.company||'物流公司待回传'} · {s.trackingNumber||'单号待回传'}</strong><p>出库单 {s.id}</p>{s.items.map((line,i)=><p key={i}>{line.skuId} × {line.quantity}</p>)}</div>)}</section>}
 {record?.changes?.length>0&&<details><summary>订单变更记录（{record.changes.length}）</summary>{[...record.changes].reverse().map(c=><section className="jst-progress" key={c.id}><strong>{{ADDRESS:'收件信息',REMARK:'发货备注',CANCEL:'取消发货'}[c.kind]} · {c.status==='CONFIRMED'?'已生效':'已拒绝'}</strong><p>{c.reason} · {c.operator}</p><small>{new Date(c.createdAt).toLocaleString()}</small>{c.kind==='ADDRESS'&&<p>原收件信息：{c.before?.receiver_name} · {c.before?.receiver_mobile} · {c.before?.receiver_state}{c.before?.receiver_city}{c.before?.receiver_district}{c.before?.receiver_address}<br/>变更为：{c.target?.receiver_name} · {c.target?.receiver_mobile} · {c.target?.receiver_state}{c.target?.receiver_city}{c.target?.receiver_district}{c.target?.receiver_address}</p>}{c.kind==='REMARK'&&<p>原备注：{c.before?.remark}<br/>变更为：{c.target?.remark}</p>}</section>)}</details>}
 {record?.events?.length>0&&<details><summary>同步记录</summary>{[...record.events].reverse().map((event,i)=><p key={i}><small>{new Date(event.time).toLocaleString()} · {event.message}</small></p>)}</details>}
 {error&&<p role="alert" className="jst-error">{error}</p>}
 </div><div className="modal-footer"><button className="secondary" type="button" disabled={busy} onClick={onClose}>关闭</button>{record&&canWrite&&<button className="secondary" type="button" disabled={busy} onClick={sync}>{busy?'处理中…':'同步状态'}</button>}{changeKind&&<><button type="button" className="secondary" disabled={busy} onClick={()=>setChangeKind('')}>放弃变更</button><button type="submit" className="primary" disabled={busy}>{busy?'正在处理…':manualCancellation?'核对取消并保存变更':'提交变更并同步'}</button></>}{canSubmit&&!changeKind&&<button className="primary" type="submit" disabled={busy}>{busy?'正在提交…':record?'重试原订单':'提交发货'}</button>}</div></form></DialogLayer>;
}
