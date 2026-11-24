import React,{useEffect,useRef,useState} from 'react';
import PaymentMethodPicker from './PaymentMethodPicker';
import OrderSelect from './OrderSelect';
import {customerOptions} from './customerOptions';
import {moneyCents} from './orderMoney';
import {recordId} from './recordId';
import {orderExecutionView} from './feishuApprovalModel';
import {orderNumber} from './orderNumber';
import ReceiptApprovalPanel from './ReceiptApprovals';
import {buildDirectReceiptChange,buildReceiptApprovalRequest,receiptApprovalLabels,receiptApprovalProblem,receiptPaymentMethods,reservedReceiptOrderIds} from './receiptApprovalModel';
import ReceiptProductDetails from './ReceiptProductDetails';
import ReceiptLedger from './ReceiptLedger';
import {canReceiveOrder,itemsFromOrder,productSummary} from './receiptProductModel';

const money=value=>'¥ '+Number(value||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const cents=value=>moneyCents(value)??0;

export default function ReceiptOrders({api,db,Icon,save,DialogLayer,paymentMethods,entryOnly=false,onEntryClose,onRegistered,
  approvals,canRegister=true,attempt=null,onAttemptChange,initialOrderId,shipments={},onOpenOrder}){
  const preset=db.orders.find(order=>order.id===initialOrderId);
  const[selecting,setSelecting]=useState(entryOnly),
    [customer,setCustomer]=useState(attempt?.customerId||preset?.customerId||''),[payments,setPayments]=useState(()=>attempt?Object.fromEntries(attempt.allocations.map(row=>[row.orderId,String(row.amount)])):preset?{[preset.id]:''}:{}),
    [received,setReceived]=useState(attempt?String(attempt.amount):''),[payMethod,setPayMethod]=useState(attempt?.method||paymentMethods[0]||''),
    [saving,setSaving]=useState(false),[formError,setFormError]=useState('');
  const[receiptDate,setReceiptDate]=useState(attempt?.receiptDate||''),[receiptCategory,setReceiptCategory]=useState(attempt?.receiptCategory||''),[note,setNote]=useState(attempt?.note||''),
    [expandedOrder,setExpandedOrder]=useState(null),[checkingSubmission,setCheckingSubmission]=useState(Boolean(attempt)),[feedback,setFeedback]=useState('');
  const submission=useRef(attempt),submitting=useRef(false);
  useEffect(()=>{
    const pending=submission.current;
    if(!pending||!checkingSubmission)return;
    const record=approvals.rows.find(row=>row.id===pending.requestId||row.requestId===pending.requestId);
    if(record)completeSubmission(record);
  },[approvals.rows,checkingSubmission]);
  const eligible=db.orders.map(order=>({...order,...orderExecutionView(order,shipments[order.id])})).filter(canReceiveOrder);
  const customerOrders=eligible.filter(order=>order.customerId===customer);
  const selected=customerOrders.filter(order=>Object.hasOwn(payments,order.id));
  const reserved=reservedReceiptOrderIds(approvals.rows),methods=receiptPaymentMethods(paymentMethods,selected);
  const needsReceivedDate=selected.some(order=>order.source==='jushuitan-settlement');
  const totals=selected.reduce((sum,order)=>({total:sum.total+cents(order.total),paid:sum.paid+cents(order.paid),unpaid:sum.unpaid+cents(order.total)-cents(order.paid),payment:sum.payment+cents(payments[order.id])}),{total:0,paid:0,unpaid:0,payment:0});
  const receivedCents=moneyCents(received),receivedValid=received!==''&&receivedCents!==null&&receivedCents>0;
  const difference=(receivedCents??0)-totals.payment,reconciled=receivedValid&&totals.payment>0&&difference===0;
  const formLocked=saving||checkingSubmission;
  const approvalRequired=!(approvals.config?.enabled===false&&approvals.config?.required===false);
  const problem=receiptApprovalProblem(approvals.config,approvals.error,canRegister);
  const balance=db.customers.find(row=>row.id===customer)?.balance??0;
  function completeSubmission(record){
    if(!submission.current)return;
    submission.current=null;onAttemptChange?.(null);setCheckingSubmission(false);setFormError('');
    const message=record.status==='APPROVED'&&record.booked!==true?'收款审批已通过，入账待处理，请在审批详情中核对。':
      '收款申请已记录：'+(receiptApprovalLabels[record.status]||'请查看审批状态')+'。审批通过后入账，不触发发货。';
    setFeedback(message);setSelecting(false);setPayments({});setReceived('');onRegistered?.(record);
  }
  async function submitPayment(event){
    event.preventDefault();if(submitting.current)return;setFormError('');
    if(!canRegister){setFormError('当前账号没有登记收款权限');return}
    let payload=submission.current;
    if(!payload&&!approvalRequired){
      if(problem){setFormError(problem);return}
      submitting.current=true;setSaving(true);
      try{
        const change=buildDirectReceiptChange({db,customerId:customer,selected,payments,amount:received,method:payMethod,
          receiptDate,methods:paymentMethods,config:approvals.config,records:approvals.rows});
        await save(change.data,change.verification);setSelecting(false);setPayments({});setReceived('');
        setFeedback('收款已登记。');onRegistered?.({direct:true});
      }catch(failure){setFormError(failure.message)}finally{submitting.current=false;setSaving(false)}
      return;
    }
    if(!payload){
      if(problem){setFormError(problem);return}
      try{payload=buildReceiptApprovalRequest({requestId:recordId(''),db,customerId:customer,selected,payments,amount:received,
        method:payMethod,receiptDate,receiptCategory,note,methods:paymentMethods,config:approvals.config,records:approvals.rows})}
      catch(failure){setFormError(failure.message);return}
      submission.current=payload;onAttemptChange?.(payload);
    }
    submitting.current=true;setSaving(true);
    try{
      const result=await approvals.request(null,null,payload);
      if(result?.approval)completeSubmission(result.approval);
    }catch(failure){
      if(!failure.status||failure.status>=500){
        setCheckingSubmission(true);setFormError('收款申请结果待核对，已保留原申请和填写内容。请核对原申请，避免重复提交。'+failure.message);
      }else{submission.current=null;onAttemptChange?.(null);setCheckingSubmission(false);setFormError(failure.message)}
    }finally{submitting.current=false;setSaving(false)}
  }
  function closeEntry(){if(saving)return;setSelecting(false);onEntryClose?.()}
  function openEntry(){
    if(submission.current){setCheckingSubmission(true);setSelecting(true);return}
    setCustomer('');setPayments({});setReceived('');setPayMethod(paymentMethods[0]||'');setReceiptDate('');setReceiptCategory('');setNote('');setExpandedOrder(null);setFormError('');setSelecting(true);
  }
  const receiptForm=<DialogLayer kind="drawer" label="收款审批申请" open={selecting} onClose={closeEntry}><form className="drawer receipt-approval-form" onSubmit={submitPayment}>
    <div className="modal-title"><h2>{approvalRequired||checkingSubmission?'新建收款申请':'新建收款'}</h2><button type="button" disabled={saving} aria-label="关闭收款申请" onClick={closeEntry}><Icon name="close"/></button></div>
    <div className="modal-body">
      {approvalRequired&&<p className="receipt-approval-notice">提交后发起飞书收款审批，审批通过前不计入已收金额。预收款核销同样通过审批后扣减余额；收款审批不触发发货。</p>}
      {problem&&<p className="system-error" role="status">{problem} <button type="button" className="link-btn" disabled={saving} onClick={approvals.retry}>重新加载</button></p>}
      {checkingSubmission&&<p className="receipt-approval-notice" role="status">原收款申请结果待核对，已锁定本次填写内容。可核对原申请，关闭后也会保留原申请编号。</p>}
      <div className="field"><span>客户 <em>*</em></span><PaymentMethodPicker label="客户" options={customerOptions(db.customers)} value={customer} disabled={formLocked} onChange={value=>{setCustomer(value);setPayments({});setReceived('');setExpandedOrder(null);setFormError('')}}/></div>
      <p className="form-hint">勾选已批准且未结清的订单，填写每笔订单本次付款金额。已有未完成收款审批的订单暂不能重复申请。</p>
      <div className="table-scroll"><table className="receipt-selection"><thead><tr>{['选择','订单编号','产品明细','订单总金额','已付金额','未收金额','本次付款'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{customerOrders.map(order=><React.Fragment key={order.id}><tr className={reserved.has(order.id)?'receipt-reserved-order':''}>
        <td><input type="checkbox" aria-label={'选择订单 '+orderNumber(order)} disabled={formLocked||reserved.has(order.id)} checked={Object.hasOwn(payments,order.id)} onChange={event=>setPayments(previous=>{const next={...previous};if(event.target.checked)next[order.id]='';else delete next[order.id];return next})}/></td>
        <td>{orderNumber(order)}<small>{order.type}</small>{reserved.has(order.id)&&<small className="receipt-reserved-label">已有未完成的收款审批</small>}</td>
        <td><span className="receipt-products-summary">{productSummary(itemsFromOrder(order,db.products))}</span><button type="button" className="link-btn receipt-order-products-toggle" aria-label={'查看订单产品 '+orderNumber(order)} aria-expanded={expandedOrder===order.id} aria-controls={'receipt-products-'+encodeURIComponent(order.id)} onClick={()=>setExpandedOrder(previous=>previous===order.id?null:order.id)}>{expandedOrder===order.id?'收起产品明细':'查看产品明细'}</button></td>
        <td>{money(order.total)}</td><td>{money(order.paid)}</td><td>{money((cents(order.total)-cents(order.paid))/100)}</td>
        <td><input aria-label={orderNumber(order)+' 本次付款'} type="number" min="0.01" step="0.01" max={((cents(order.total)-cents(order.paid))/100).toFixed(2)} required disabled={formLocked||reserved.has(order.id)||!Object.hasOwn(payments,order.id)} placeholder="请输入金额" value={payments[order.id]??''} onChange={event=>setPayments(previous=>({...previous,[order.id]:event.target.value}))}/></td>
      </tr>{expandedOrder===order.id&&<tr className="receipt-order-product-row"><td colSpan={7}><div id={'receipt-products-'+encodeURIComponent(order.id)}><ReceiptProductDetails items={itemsFromOrder(order,db.products)}/></div></td></tr>}</React.Fragment>)}</tbody></table>{!customerOrders.length&&<div className="empty">{customer?'该客户暂无已批准且未结清的订单':'请先选择客户'}</div>}</div>
      <div className="receipt-totals">{[['所选订单总金额',totals.total],['已付金额',totals.paid],['未收金额',totals.unpaid],['本次付款合计',totals.payment]].map(([label,value])=><div key={label}><span>{label}</span><strong>{money(value/100)}</strong></div>)}</div>
      <div className="receipt-payment-row"><div className="field"><span>收款方式 <em>*</em></span><PaymentMethodPicker options={methods} value={payMethod} onChange={setPayMethod} disabled={formLocked}/></div><label className="field"><span>{payMethod==='预收款核销'?'本次核销金额':'实际收款金额'} <em>*</em></span><input aria-label="实际收款金额" type="number" min="0.01" step="0.01" required disabled={formLocked} placeholder="请输入金额" value={received} onChange={event=>{setReceived(event.target.value);setFormError('')}}/></label></div>
      {payMethod==='预收款核销'&&<p className="receipt-approval-date-note">客户当前预收款余额 {money(balance)}；本次使用已有余额抵扣，不新增现金收款。{approvalRequired?'审批通过时再次核验余额，审批中暂不扣减。':'提交时再次核验余额。'}</p>}
      {(approvalRequired||needsReceivedDate)&&<div className="receipt-approval-fields"><label className="field"><span>{payMethod==='预收款核销'?'核销日期':'实际到账日期'} <em>*</em></span><input type="date" aria-label={payMethod==='预收款核销'?'核销日期':'实际到账日期'} required disabled={formLocked} value={receiptDate} onChange={event=>setReceiptDate(event.target.value)}/></label>
        {approvalRequired&&<OrderSelect label="收款类别" name="receiptCategory" required disabled={formLocked} placeholder="请选择收款类别" value={receiptCategory} options={(approvals.config?.receiptCategories||[]).map(value=>({value,label:value}))} onChange={setReceiptCategory}/>}
      </div>}
      {needsReceivedDate&&<p className="receipt-approval-date-note">所选包含聚水潭结算单，请填写真实到账日期。结算单不支持预收款核销。</p>}
      <label className="field"><span>收款申请备注</span><textarea aria-label="收款申请备注" maxLength={2000} disabled={formLocked} value={note} onChange={event=>setNote(event.target.value)} placeholder="填写本次收款说明，不修改原订单备注"/></label>
      <div className={'receipt-reconciliation '+(received===''?'':reconciled?'is-matched':'is-mismatched')} role="status" aria-live="polite">{received===''?'请填写本次金额，与上方本次付款合计核对。':!receivedValid?'请输入大于零且最多两位小数的金额。':reconciled?'金额核对一致：本次金额与付款合计均为 '+money(totals.payment/100):'金额不一致：本次金额 '+money(Number(received))+'，付款合计 '+money(totals.payment/100)+'，'+(difference>0?'多收 ':'少收 ')+money(Math.abs(difference)/100)}</div>
      {formError&&<p className="system-error" role="alert">{formError}</p>}
    </div><div className="modal-footer"><button className="secondary" type="button" disabled={saving} onClick={closeEntry}>{checkingSubmission?'关闭并保留申请':'取消'}</button><button className="primary" type="submit" disabled={saving||!canRegister||(!checkingSubmission&&(Boolean(problem)||!selected.length||!reconciled))}>{saving?'正在提交…':checkingSubmission?'核对原收款申请':approvalRequired?'提交并发起审批':'确认收款'}</button></div>
  </form></DialogLayer>;
  if(entryOnly)return receiptForm;
  return <>
    <div className="page-heading"><div><div className="breadcrumb">业务管理 <span>/</span> 收款管理 <span>/</span> 收款</div><h1>收款</h1><p>统一登记收款、跟踪审批，筛选并核对已入账流水。</p></div><button type="button" className="primary" disabled={!canRegister||(!submission.current&&Boolean(problem))} title={problem||undefined} onClick={openEntry}><Icon name="plus" size={17}/>{submission.current?'继续核对收款申请':'新建收款'}</button></div>
    {feedback&&<p className="receipt-approval-feedback" role="status">{feedback}</p>}
    <ReceiptApprovalPanel approvals={approvals} api={api} orders={db.orders} DialogLayer={DialogLayer} Icon={Icon}/>
    {receiptForm}
    <ReceiptLedger api={api} orders={db.orders} Icon={Icon} DialogLayer={DialogLayer} revision={db.revision} onOpenOrder={onOpenOrder}/>
  </>;
}
