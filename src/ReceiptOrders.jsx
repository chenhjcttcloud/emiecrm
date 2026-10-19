import React,{useEffect,useState} from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';

const money=value=>'¥ '+Number(value||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const time=value=>value?new Date(value).toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}):'--';

export default function ReceiptOrders({api,db,Icon,save,DialogLayer,paymentMethods}){
  const[receipts,setReceipts]=useState([]),[query,setQuery]=useState(''),[method,setMethod]=useState(''),[selecting,setSelecting]=useState(false),[customer,setCustomer]=useState(''),[payments,setPayments]=useState({}),[received,setReceived]=useState(''),[payMethod,setPayMethod]=useState('银行转账'),[saving,setSaving]=useState(false),[formError,setFormError]=useState(''),[loading,setLoading]=useState(true),[error,setError]=useState('');
  useEffect(()=>{let active=true;setLoading(true);setError('');api('receipts').then(data=>{if(active)setReceipts(data)}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[db.revision]);
  const rows=receipts.filter(r=>(!method||r.method===method)&&[r.order,r.customer,r.operator,r.method].some(v=>String(v||'').toLowerCase().includes(query.toLowerCase())));
  const eligible=db.orders.filter(r=>r.status==='已批准'&&Number(r.paid||0)<Number(r.total));
  const cents=value=>Math.round(Number(value||0)*100);
  const customerOrders=eligible.filter(r=>r.customer===customer);
  const selected=customerOrders.filter(r=>Object.hasOwn(payments,r.id));
  const totals=selected.reduce((s,r)=>({total:s.total+cents(r.total),paid:s.paid+cents(r.paid),unpaid:s.unpaid+cents(r.total)-cents(r.paid),payment:s.payment+cents(payments[r.id])}),{total:0,paid:0,unpaid:0,payment:0});
  const receivedValid=received!==''&&Number.isFinite(Number(received))&&Number(received)>0&&/^\d+(?:\.\d{1,2})?$/.test(received);
  const difference=cents(received)-totals.payment;
  const reconciled=receivedValid&&totals.payment>0&&difference===0;
  async function submitPayment(e){
    e.preventDefault();if(saving)return;setFormError('');
    if(!paymentMethods.includes(payMethod)){setFormError('请选择已启用的收款方式；如无可用方式，请在系统管理中启用');return}
    if(!selected.length){setFormError('请至少选择一笔订单');return}
    if(selected.some(r=>!Number.isFinite(Number(payments[r.id]))||cents(payments[r.id])<=0||cents(payments[r.id])>cents(r.total)-cents(r.paid))){setFormError('本次付款须大于零，且不能超过该订单未收金额');return}
    if(!reconciled){setFormError('实际收款金额必须与上方本次付款合计一致，请核对后提交');return}
    setSaving(true);
    try{const ids=new Set(selected.map(r=>r.id));await save({...db,orders:db.orders.map(r=>ids.has(r.id)?{...r,paid:(cents(r.paid)+cents(payments[r.id]))/100,method:payMethod,updated:new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'})}:r)},{amount:Number(received),customer,method:payMethod});setSelecting(false);setPayments({});setReceived('')}catch(e){setFormError(e.message)}finally{setSaving(false)}
  }
  function exportRows(){const table=[['订单编号','客户名称','订单类型','收款金额','收款方式','收款时间','操作人'],...rows.map(r=>[r.order,r.customer,r.type,r.amount,r.method,time(r.time),r.operator])];const url=URL.createObjectURL(new Blob(['\uFEFF'+table.map(row=>row.map(v=>'"'+String(v??'').replaceAll('"','""')+'"').join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='收款订单.csv';a.click();URL.revokeObjectURL(url)}
  return <>
    <div className="page-heading"><div><div className="breadcrumb">业务管理 <span>/</span> 收款管理 <span>/</span> 收款订单</div><h1>收款订单</h1><p>登记订单收款，查看每一笔收款记录。</p></div><div className="page-heading-symbol"><Icon name="cash" size={30}/></div></div>
    <div className="summary-grid">{[['收款笔数',rows.length,'orders'],['收款总额',money(rows.reduce((sum,r)=>sum+Number(r.amount),0)),'cash'],['收款客户',new Set(rows.map(r=>r.customer)).size,'customers']].map(([label,value,icon])=><div className="summary-card" key={label}><span className="summary-icon"><Icon name={icon} size={21}/></span><div><span className="summary-label">{label}</span><strong>{value}</strong></div></div>)}</div>
    <DialogLayer kind="drawer" open={selecting} onClose={()=>{if(!saving)setSelecting(false)}}><form className="drawer" onSubmit={submitPayment}>
      <div className="modal-title"><h2>新建收款</h2><button type="button" disabled={saving} aria-label="关闭" onClick={()=>setSelecting(false)}><Icon name="close"/></button></div>
      <div className="modal-body"><div className="field"><span>客户 <em>*</em></span><PaymentMethodPicker label="客户" options={[...new Set(db.customers.map(c=>c.name))]} value={customer} disabled={saving} onChange={name=>{setCustomer(name);setPayments({});setReceived('');setFormError('')}}/></div>
      <p className="form-hint">勾选需要收款的订单，填写每笔订单本次付款金额。仅显示已批准且未结清的订单。</p>
      <div className="table-scroll"><table className="receipt-selection"><thead><tr>{['选择','订单编号','订单总金额','已付金额','未收金额','本次付款'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{customerOrders.map(r=><tr key={r.id}><td><input type="checkbox" aria-label={'选择订单 '+r.id} disabled={saving} checked={Object.hasOwn(payments,r.id)} onChange={e=>setPayments(prev=>{const next={...prev};if(e.target.checked)next[r.id]='';else delete next[r.id];return next})}/></td><td>{r.id}<small>{r.type}</small></td><td>{money(r.total)}</td><td>{money(r.paid)}</td><td>{money(r.total-Number(r.paid||0))}</td><td><input aria-label={r.id+' 本次付款'} type="number" min="0.01" step="0.01" max={((cents(r.total)-cents(r.paid))/100).toFixed(2)} required disabled={saving||!Object.hasOwn(payments,r.id)} placeholder="请输入金额" value={payments[r.id]??''} onChange={e=>setPayments(prev=>({...prev,[r.id]:e.target.value}))}/></td></tr>)}</tbody></table>{!customerOrders.length&&<div className="empty">{customer?'该客户暂无已批准且未结清的订单':'请先选择客户'}</div>}</div>
      <div className="receipt-totals">{[['所选订单总金额',totals.total],['已付金额',totals.paid],['未收金额',totals.unpaid],['本次付款合计',totals.payment]].map(([label,value])=><div key={label}><span>{label}</span><strong>{money(value/100)}</strong></div>)}</div>
      <div className="receipt-payment-row"><div className="field"><span>收款方式 <em>*</em></span><PaymentMethodPicker options={paymentMethods} value={payMethod} onChange={setPayMethod} disabled={saving}/></div><label className="field"><span>实际收款金额 <em>*</em></span><input aria-label="实际收款金额" type="number" min="0.01" step="0.01" required disabled={saving} placeholder="请输入实际收到的金额" value={received} onChange={e=>{setReceived(e.target.value);setFormError('')}}/></label></div>
      <div className={'receipt-reconciliation '+(received===''?'':reconciled?'is-matched':'is-mismatched')} role="status" aria-live="polite">{received===''?'请填写实际收款金额，与上方本次付款合计核对。':!receivedValid?'请输入大于零且最多两位小数的收款金额。':reconciled?'金额核对一致：实际收款与本次付款合计均为 '+money(totals.payment/100):'金额不一致：实际收款 '+money(Number(received))+'，本次付款合计 '+money(totals.payment/100)+'，'+(difference>0?'多收 ':'少收 ')+money(Math.abs(difference)/100)}</div>
      {formError&&<p className="system-error" role="alert">{formError}</p>}</div><div className="modal-footer"><button className="secondary" type="button" disabled={saving} onClick={()=>setSelecting(false)}>取消</button><button className="primary" type="submit" disabled={saving||!selected.length||!reconciled}>{saving?'正在提交…':'确认收款'}</button></div>
    </form></DialogLayer>
    <section className="panel"><div className="table-toolbar"><button className="primary" onClick={()=>{setCustomer('');setPayments({});setReceived('');setPayMethod(paymentMethods[0]||'');setFormError('');setSelecting(true)}}><Icon name="plus" size={17}/>新建收款</button><div className="toolbar-actions"><div className="search"><Icon name="search" size={17}/><input aria-label="搜索收款订单" placeholder="搜索订单、客户或操作人" value={query} onChange={e=>setQuery(e.target.value)}/></div><select aria-label="收款方式筛选" value={method} onChange={e=>setMethod(e.target.value)}><option value="">全部收款方式</option>{[...new Set(receipts.map(r=>r.method))].map(m=><option key={m}>{m}</option>)}</select><button title="导出CSV" className="icon-btn" onClick={exportRows} disabled={loading||!!error}><Icon name="export"/></button></div></div><div className="table-scroll"><table className="data-table"><thead><tr>{['订单编号','客户名称','订单类型','收款金额','收款方式','收款时间','操作人'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{!loading&&!error&&rows.map(r=><tr key={r.id}><td>{r.order}</td><td><span className="badge badge-blue">{r.customer}</span></td><td>{r.type}</td><td>{money(r.amount)}</td><td>{r.method}</td><td>{time(r.time)}</td><td>{r.operator}</td></tr>)}</tbody></table>{(loading||error||!rows.length)&&<div className="empty" role="status">{loading?'正在加载收款订单…':error||'暂无收款记录'}</div>}</div><div className="table-footer">共 {loading||error?'--':rows.length} 条收款记录</div></section>
  </>;
}
