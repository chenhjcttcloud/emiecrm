import {csvCell} from './csv';
import FileDropZone from './FileDropZone';
import {ApprovalFlow,ApprovalFormFields,ApprovalStatus,useFeishuApprovals} from './FeishuOrderApproval';
import {approvalDetails,approvalDraftValues,approvalLabels,approvalLocksOrder,approvalSettings,approvalInstanceUrl,canCancelOrder,canDeleteOrder,canSubmitApproval,isApprovalOrder,isSalesApprovalOrder,isSettlementApprovalOrder,submissionProblem,visibleBusinessOrders,activeBusinessOrders,orderExecutionView} from './feishuApprovalModel';
import React,{useState,useEffect,useRef} from 'react';
import AuthBoundary from './AuthBoundary';
import {api,uploadImage,uploadCustomerFile} from './authApi';
import CustomerImportDialog from './CustomerImportDialog';
import CustomerExternalLinks,{externalLinkLabel} from './CustomerExternalLinks';
import {customerExportCsv} from './customerExport';
import LegacyCustomerSource,{LegacyCustomerNote} from './LegacyCustomerSource';
import {customerFieldRequired,firstMissingCustomerField,isLegacyCustomer} from './legacyCustomerModel';
import useDatabase from './useDatabase';
import {captureBusinessForm,replaceBusinessFormRecord} from './businessFormSnapshot';
import {recordId} from './recordId';
import {nextOrderNumber,orderNumber,orderNumberForId} from './orderNumber';
import BusinessImage from './BusinessImage';
import JushuitanProducts,{matchesProductPrefix} from './JushuitanProducts';
import ShipmentDialog,{useJushuitan,shipmentLabel} from './JushuitanOrders';
import JushuitanDocuments from './JushuitanDocuments';
import JushuitanSettlement from './JushuitanSettlement';
import {customerOptions} from './customerOptions';
import {orderTotal,moneyCents} from './orderMoney';
import Pagination from './Pagination';
import{createRoot}from'react-dom/client';
import './styles.css';
import SystemManagement from './SystemManagement';
import OrderChangeLog from './OrderChangeLog';
import ReceiptOrders from './ReceiptOrders';
import {useReceiptApprovals} from './ReceiptApprovals';
import {receiptApprovalLabels,reservedReceiptOrderIds} from './receiptApprovalModel';
import PaymentMethods from './PaymentMethods';
import SalesDashboard from './SalesDashboard';
import TargetWorkspace from './TargetWorkspace';
import WorkspaceShell from './WorkspaceShell';
import SalesItems from './SalesItems';
import OrderViewTabs from './OrderViewTabs';
import {dashboardMoney,sumDashboardMoney} from './dashboardModel';
import {salesOrderFinance} from './salesModel';
import './salesOrders.css';
import OrderFilters from './OrderFilters.jsx';
import CustomerFilters from './CustomerFilters.jsx';
import {emptyCustomerFilters,matchesCustomerFilters} from './customerFilterLogic';
import usePageNavigation from './usePageNavigation';
import BulkProductPicker from './BulkProductPicker';
import {addOrderProducts} from './orderItems';
import {emptyOrderFilters,matchesOrderFilters} from './orderFilterLogic';
import OrderDetails from './OrderDetails';
import OrderActionMenu from './OrderActionMenu';
import OrderSelect from './OrderSelect';
import OrderWarehousePicker from './OrderWarehousePicker';
const owners=[];
const stamp=()=>new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'});
function Icon({name,size=18,...props}){
const paths={chart:'M4 3v18h17 M8 16v-5 M13 16V7 M18 16v-8',logout:'M9 4H4v16h5 M14 8l4 4-4 4 M8 12h12',orders:'M8 3h8v4H8z M6 5H4v16h16V5h-2 M8 12h8 M8 16h5',cash:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v10 M15 9h-4a2 2 0 0 0 0 4h2a2 2 0 0 1 0 4H9',customers:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M17 4a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87',products:'M3 7l9-4 9 4v10l-9 4-9-4V7 M3 7l9 4 9-4 M12 11v10 M7 5l10 4',search:'M11 3a8 8 0 1 0 0 16 8 8 0 0 0 0-16 M17 17l4 4',filter:'M4 5h16l-6 7v7l-4 2v-9L4 5',sort:'M8 3v18 M4 7l4-4 4 4 M16 21V3 M12 17l4 4 4-4',export:'M12 3v12 M8 11l4 4 4-4 M4 15v6h16v-6',settings:'M4 7h16 M4 17h16 M8 4v6 M16 14v6',plus:'M12 5v14 M5 12h14',edit:'M15 5l4 4 M4 20l4-1L21 6a2.8 2.8 0 0 0-4-4L4 15l-1 6z',chevron:'M8 10l4 4 4-4',close:'M6 6l12 12 M6 18L18 6',upload:'M12 16V3 M7 8l5-5 5 5 M4 15v6h16v-6',check:'M5 12l4 4L19 6',layers:'M3 7l9-4 9 4-9 4-9-4 M3 12l9 4 9-4 M3 17l9 4 9-4',clock:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l3 2',arrow:'M5 12h14 M14 7l5 5-5 5',image:'M3 3h18v18H3z M3 16l5-5 4 4 3-3 6 6 M15 7h.01'};
return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="ui-icon" {...props}><path d={paths[name]||paths.products}/></svg>}
function Badge({children,tone='blue'}){return <span className={'badge badge-'+tone}>{children}</span>}
function Person({name=''}){return <span className="person"><span className="avatar" style={{background:['#9b91c3','#c8ac83','#87a7b8','#b1a28f'][Math.max(0,[...name].reduce((sum,c)=>sum+c.charCodeAt(0),0))%4]}}>{name.slice(-2)}</span>{name}</span>}
function OwnerBadge({name=''}){
const palette=[['#e9efff','#3457b1','#cbd8ff'],['#e4f5f1','#217760','#bde5d9'],['#f0eaff','#7150aa','#ded0fa'],['#fff0df','#985b1b','#f3d8b9'],['#fde9ef','#a64565','#f6cbd8'],['#e2f3fc','#286c94','#bedff2'],['#eef3dd','#5e7529','#d7e4b2'],['#fcebe4','#9e563c','#f0d1c3']];
const hash=[...name].reduce((sum,char)=>sum*31+char.codePointAt(0),0)>>>0;
const[background,color,borderColor]=palette[hash%palette.length];
return <span className="owner-name-badge" title={name||'未分配'} aria-label={'负责人：'+(name||'未分配')} style={{background,color,borderColor}}>{name||'未分配'}</span>;
}
function DialogLayer({open,onClose,children,kind='modal',label}){
  const [present,setPresent]=useState(open);
  const content=useRef(children), close=useRef(onClose), layer=useRef(null);
  if(open)content.current=children;
  close.current=onClose;
  useEffect(()=>{if(open){setPresent(true);return}const timer=setTimeout(()=>setPresent(false),180);return()=>clearTimeout(timer)},[open]);
  useEffect(()=>{
    if(!open&&!present)return;
    const previous=document.activeElement,overflow=document.body.style.overflow;
    document.body.style.overflow='hidden';
    const focusable=()=>[...layer.current.querySelectorAll('button,input,select,textarea,a[href],[tabindex]')].filter(el=>!el.disabled&&el.tabIndex>=0&&el.getClientRects().length);
    const frame=requestAnimationFrame(()=>layer.current?.focus({preventScroll:true}));
    const keydown=e=>{if(layer.current!==[...document.querySelectorAll('.dialog-layer.is-open')].at(-1))return;if(e.key==='Escape'){e.preventDefault();close.current();return}if(e.key==='Tab'){const list=focusable();if(!list.length){e.preventDefault();return}const first=list[0],last=list.at(-1);if(e.shiftKey&&(document.activeElement===first||document.activeElement===layer.current)){e.preventDefault();last.focus()}else if(!e.shiftKey&&(document.activeElement===last||document.activeElement===layer.current)){e.preventDefault();first.focus()}}};
    document.addEventListener('keydown',keydown);
    return()=>{cancelAnimationFrame(frame);document.body.style.overflow=overflow;document.removeEventListener('keydown',keydown);if(previous?.isConnected)previous.focus({preventScroll:true})};
  },[open||present]);
  if(!open&&!present)return null;
  return <div ref={layer} tabIndex={-1} role="dialog" aria-modal="true" aria-label={label||(kind==='drawer'?'订单与收款表单':'记录表单')} className={'overlay dialog-layer '+(open?'is-open':'is-closing')+' dialog-'+kind} onMouseDown={e=>{if(e.target===e.currentTarget&&open)close.current()}}>{content.current}</div>;
}
function App({user,onLogout}){
 const[page,setPage]=usePageNavigation(user.permissions);
 const[orderIntent,setOrderIntent]=useState(null);
 const[salesTargetMonth,setSalesTargetMonth]=useState(null);
 useEffect(()=>{if(page!=='targets-personnel')setSalesTargetMonth(null)},[page]);
 const openSalesTargets=month=>{setSalesTargetMonth(month||null);setPage('targets-personnel')};
 const openSalesOrder=id=>{setOrderIntent({id,key:Date.now()});setPage('sales-orders')};
 if(!page)return <main className="login-screen"><section className="auth-card"><h1>暂无可访问的模块</h1><p>请联系管理员配置角色权限。</p><button type="button" className="secondary" onClick={onLogout}>退出登录</button></section></main>;
 if(page.startsWith('dashboard-')||page.startsWith('sys-')||['targets-company','targets-personnel'].includes(page))return <WorkspaceShell user={user} onLogout={onLogout} page={page} go={setPage} Icon={Icon}>
   {['targets-company','targets-personnel'].includes(page)?<TargetWorkspace api={api} Icon={Icon} DialogLayer={DialogLayer} user={user} view={page==='targets-company'?'company':'personnel'} initialMonth={salesTargetMonth} onPersonnelMonth={openSalesTargets} onViewChange={view=>setPage(view==='company'?'targets-company':'targets-personnel')}/>:page==='dashboard-sales'?<SalesDashboard api={api} Icon={Icon} DialogLayer={DialogLayer} canManageTargets={user.permissions.includes('dashboard.targets.manage')} onManageTargets={()=>setPage('targets-company')} onSalesTargets={user.permissions.includes('business.read')?openSalesTargets:undefined}/>:page==='sys-payment-methods'?<PaymentMethods api={api} Icon={Icon} DialogLayer={DialogLayer}/>:<SystemManagement page={page} api={api} Icon={Icon} DialogLayer={DialogLayer}/>}
 </WorkspaceShell>;
 return <BusinessApp user={user} onLogout={onLogout} page={['sales-orders','order-items'].includes(page)?'orders':page==='receipt-orders'?'receipts':page} navigationPage={page} salesMode={page==='sales-orders'||page==='order-items'} setPage={setPage} orderIntent={orderIntent} onOrderIntentHandled={()=>setOrderIntent(null)} onOpenSalesOrder={openSalesOrder}/>;
}
function BusinessApp({user,onLogout,page,setPage,navigationPage=page,salesMode=false,orderIntent,onOrderIntentHandled,onOpenSalesOrder}){const[customerFilters,setCustomerFilters]=useState(emptyCustomerFilters);const[bulkProducts,setBulkProducts]=useState(false);const[orderFilters,setOrderFilters]=useState(emptyOrderFilters);const[db,save,setDatabase,loading,loadError,retryLoad,refreshData,refreshing,refreshError]=useDatabase(user.permissions.includes('business.write'));const[query,search]=useState('');const[filter,setFilter]=useState('');const[sort,setSort]=useState(false);const[modal,setModal]=useState(null);const[draft,setDraft]=useState({});const[message,notify]=useState('');const[showFilter,toggleFilter]=useState(false);
const[detailsOrder,setDetailsOrder]=useState(null);
const listedOrders=visibleBusinessOrders(db.orders);const activeOrders=activeBusinessOrders(db.orders);
const[jstOrder,setJstOrder]=useState(null);const[documentOrder,setDocumentOrder]=useState(null);const[settlementOpen,setSettlementOpen]=useState(false);const jst=useJushuitan(api,db.revision);
const[customerImportOpen,setCustomerImportOpen]=useState(false);
const[receiptEntry,setReceiptEntry]=useState(false);const[receiptAttempt,setReceiptAttempt]=useState(null);const[salesOrderType,setSalesOrderType]=useState('销售订单');const handledOrderIntent=useRef(null);
const[submitting,setSubmitting]=useState(false);const[submitError,setSubmitError]=useState('');const[unconfirmed,setUnconfirmed]=useState(false);const submittingRequest=useRef(false);const approvalAttempt=useRef(null);
const approvals=useFeishuApprovals(api,user.permissions.includes('business.read'),setDatabase);
const receiptApprovals=useReceiptApprovals(api,user.permissions.includes('business.read'),setDatabase);
const canRegisterReceipt=['business.read','business.write','payment.write'].every(permission=>user.permissions.includes(permission));
const receiptReservations=reservedReceiptOrderIds(receiptApprovals.rows);
useEffect(()=>{if(receiptAttempt&&receiptApprovals.rows.some(row=>row.id===receiptAttempt.requestId||row.requestId===receiptAttempt.requestId))setReceiptAttempt(null)},[receiptAttempt,receiptApprovals.rows]);
const draftApprovalRecord=approvals.records[draft.id];
const orderLocked=modal==='order'&&(['已批准','已取消'].includes(draft.status)||approvalLocksOrder(draftApprovalRecord)||unconfirmed);
const orderSubmissionProblem=modal==='order'&&!orderLocked?submissionProblem(approvals.config,draft,approvals.error):'';
const[uploading,setUploading]=useState(false);const[listPage,setListPage]=useState(1);
const[opening,setOpening]=useState(false);const[openingTarget,setOpeningTarget]=useState('');const openingRequest=useRef(false);const dialogActive=useRef(false);const formSnapshot=useRef(null);const activePage=useRef(navigationPage);activePage.current=navigationPage;
const[tablePageSize,setTablePageSize]=useState(20);
const[customerPageSize,setCustomerPageSize]=useState(50);
const isCustomerPage=page==='customers';
const pageSize=isCustomerPage?customerPageSize:tablePageSize;
const setPageSize=isCustomerPage?setCustomerPageSize:setTablePageSize;
const hasCustomerFilters=Boolean(query||filter||Object.values(customerFilters).some(Boolean));
function clearCustomerSearch(){
  search('');
  setFilter('');
  setCustomerFilters(emptyCustomerFilters());
  setListPage(1);
}
const [admin,setAdmin]=useState(false);const[receipts,setReceipts]=useState([]);const[accounts,setAccounts]=useState([]);const[logs,setLogs]=useState([]);
const formData=formSnapshot.current||db;
useEffect(()=>{if(!modal&&!detailsOrder&&!settlementOpen&&!customerImportOpen&&!receiptEntry&&!jstOrder&&!documentOrder&&!admin&&!opening){dialogActive.current=false;formSnapshot.current=null}},[modal,detailsOrder,settlementOpen,customerImportOpen,receiptEntry,jstOrder,documentOrder,admin,opening]);
const mainArea=useRef(null);useEffect(()=>{mainArea.current?.scrollTo({top:0,left:0,behavior:'instant'})},[page]);
const[staff,setStaff]=useState([]);useEffect(()=>{let active=true;api('system/users').then(users=>{if(active)setStaff(users.filter(u=>u.status==='启用').map(u=>u.displayName))}).catch(()=>{});return()=>{active=false}},[page]);const memberNames=[...new Set([...owners,...staff,...formData.orders.map(o=>o.owner),...formData.customers.map(c=>c.owner),user.displayName||user.name].filter(Boolean))];
const[paymentMethods,setPaymentMethods]=useState([]);useEffect(()=>{let active=true;api('system/payment-methods').then(rows=>{if(active)setPaymentMethods(rows.filter(r=>r.status==='启用').map(r=>r.name))}).catch(e=>{if(active){setPaymentMethods([]);toast(e.message)}});return()=>{active=false}},[page,modal]);
useEffect(()=>{search('');setFilter('');toggleFilter(false);setOrderFilters(emptyOrderFilters());setCustomerFilters(emptyCustomerFilters());setModal(null);setDetailsOrder(null);setBulkProducts(false);setReceiptEntry(false);setCustomerImportOpen(false)},[page]);const isSystem=page.startsWith('sys-');
const orderItemsView=page==='orders'&&navigationPage==='order-items';
const title={orders:'订单管理',receipts:'收款',advance:'预收款',customers:'客户管理',products:'产品管理','sys-users':'用户管理','sys-roles':'角色权限','sys-departments':'部门管理','sys-audit':'操作日志','sys-backup':'数据备份'}[page];
function toast(text){notify(text);setTimeout(()=>notify(''),3000)}
function go(p){if(submittingRequest.current||unconfirmed)return toast('请先完成或核对当前订单提交');if(receiptAttempt)return toast('请先完成或核对本次收款申请');setOrderFilters(emptyOrderFilters());setCustomerFilters(emptyCustomerFilters());setPage(p);search('');setFilter('');toggleFilter(false)}
function changeOrderView(view){
 if(submittingRequest.current||unconfirmed)return toast('请先完成或核对当前订单提交');
 if(receiptAttempt)return toast('请先完成或核对本次收款申请');
 setPage(view==='items'?'order-items':'sales-orders');
}
function openItemOrder(id){
 if(submittingRequest.current||unconfirmed)return toast('请先完成或核对当前订单提交');
 if(receiptAttempt)return toast('请先完成或核对本次收款申请');
 onOpenSalesOrder(id);
}
function activateDialog(show,current){
 formSnapshot.current=captureBusinessForm(current);dialogActive.current=true;
 try{show(formSnapshot.current)}catch(error){dialogActive.current=false;formSnapshot.current=null;throw error}
}
function withCurrentData(show){
 if(openingRequest.current||dialogActive.current)return;
 try{activateDialog(show,db)}catch(error){toast(error.message)}
}
async function withFreshData(show,target){
 if(openingRequest.current||dialogActive.current)return;
 openingRequest.current=true;setOpening(true);setOpeningTarget(target);
 const requestedPage=navigationPage;
 try{
  const current=await refreshData();
  if(activePage.current!==requestedPage)return;
  if(!current){toast('数据刷新失败，请重试后再打开表单');return}
  activateDialog(show,current);
 }catch(error){toast(error.message)}
 finally{openingRequest.current=false;setOpening(false);setOpeningTarget('')}
}
function open(kind,row){if(submittingRequest.current||unconfirmed)return;const show=current=>{
 setSubmitError('');approvalAttempt.current=null;
 const collection=kind==='customer'?'customers':kind==='product'?'products':'orders';
 const latest=row?current[collection].find(entry=>entry.id===row.id&&!entry.deletedAt):null;
 if(row&&!latest)throw Error('这条记录已更新或移除，请刷新列表后重试');
 if(kind==='order'&&latest?.status==='已取消')throw Error('已取消订单不能编辑，可查看订单详情');
 if(kind==='payment'){
  if(!canRegisterReceipt)throw Error('当前账号没有登记收款权限');
  if(receiptAttempt){setReceiptEntry(true);return}
  const execution=latest&&orderExecutionView(latest,jst.records[latest.id]);
  if(!latest||latest.status!=='已批准'||moneyCents(execution.paid)>=moneyCents(execution.total))throw Error('订单状态已变化，仅已批准且未结清的订单可以登记收款');
  if(receiptReservations.has(latest.id))throw Error('该订单已有未完成的收款审批，请在收款管理中查看');
  setReceiptEntry(latest.id);return;
 }
 if(salesMode&&page==='orders'&&kind==='order')setSalesOrderType(latest?(latest.type==='预收款订单'?'all':latest.type):'销售订单');
 const next=latest?{...structuredClone(latest),...(kind==='payment'?{method:paymentMethods[0]||''}:{})}:kind==='customer'?{name:'',entity:'',owner:'',status:'',channel:'',links:'',contact:'',position:'',phone:'',wechat:'',balance:0}:kind==='product'?{name:'',code:'',category:'',unit:'',price:'',model:'',cost:'',image:''}:{type:page==='advance'?'预收款订单':'销售订单',owner:user.displayName||user.name,customer:'',note:'',total:'',method:'',items:page==='advance'?[]:[{product:'',qty:'',price:''}],attachments:[]};
 if(['order','payment'].includes(kind)&&!Array.isArray(next.items))next.items=[];
 setModal(kind);setDraft(next);
};return row?withFreshData(show,kind+':'+row.id):withCurrentData(show)}
function viewOrder(row){
 return withFreshData(current=>{
  const latest=current.orders.find(order=>order.id===row.id&&!order.deletedAt);
  if(!latest)throw Error('订单已更新或移除，请刷新列表后重试');
  setDetailsOrder(structuredClone(latest));
 },'details:'+row.id);
}
useEffect(()=>{if(!orderIntent||loading||loadError||page!=='orders'||handledOrderIntent.current===orderIntent.key)return;handledOrderIntent.current=orderIntent.key;onOrderIntentHandled?.();void viewOrder({id:orderIntent.id})},[orderIntent?.key,loading,loadError,page]);
const canRefresh=!loading&&!detailsOrder&&!modal&&!jstOrder&&!documentOrder&&!settlementOpen&&!admin&&!opening&&!receiptEntry&&!customerImportOpen;
useEffect(()=>{
 if(!canRefresh)return;
 const refreshVisible=()=>{if(document.visibilityState!=='hidden')void refreshData({maxAgeMs:3000})};
 if(page==='customers')refreshVisible();
 window.addEventListener('focus',refreshVisible);
 document.addEventListener('visibilitychange',refreshVisible);
 return()=>{window.removeEventListener('focus',refreshVisible);document.removeEventListener('visibilitychange',refreshVisible)};
},[page,canRefresh,refreshData]);
function patch(k,v){
 if(submittingRequest.current||unconfirmed||draft.status==='已取消'||(orderLocked&&!['note','attachments'].includes(k)))return;
 setSubmitError('');
 setDraft(d=>{const next={...d,[k]:v};if(k==='customerId'){next.customer=formData.customers.find(c=>c.id===v)?.name||'';if(isSalesApprovalOrder(d))next.approvalDraft={...d.approvalDraft,recipientName:undefined,recipientPhone:undefined,recipientProvince:undefined,recipientCity:undefined,recipientDistrict:undefined,recipientAddress:undefined}}if(k==='type'&&next.approvalDraft){if(v!=='销售订单'){const{shopId,shopName,creditFulfillment,...other}=next.approvalDraft;next.approvalDraft=other}if(!['销售订单','海外提货单'].includes(v)){const{warehouseId,warehouseName,...other}=next.approvalDraft;next.approvalDraft=other}}return next});
}
function closeForm(){if(!uploading&&!submittingRequest.current&&!unconfirmed)setModal(null)}
async function saveAndSubmitOrder(entry,details={}){
 const attempt=approvalAttempt.current??{orderId:entry.id,requestId:recordId('approval-')};
 approvalAttempt.current=attempt;
 entry.id=attempt.orderId;
 entry.approvalRequestId=attempt.requestId;
 const approval={...details,...(draftApprovalRecord?.retryVersion?{retryVersion:draftApprovalRecord.retryVersion}:{})};
 const next=replaceBusinessFormRecord(formData,'orders',entry);
 attempt.body??={data:structuredClone(next),revision:formData.revision,approval};
 try{
  const result=await approvals.request(entry.id,'create-and-submit',attempt.body);
  if(!result)return false;
  setUnconfirmed(false);approvalAttempt.current=null;
  toast('订单已保存，'+(approvalLabels[result.approval.status]||'飞书审批状态已更新'));
  return true;
 }catch(error){
  const clientError=error.status>0&&error.status<500;
  const latest=await api('state').catch(()=>null);
  if(latest){
   setDatabase(latest);
   if(latest.orders.some(row=>row.id===entry.id&&row.approvalRequestId===attempt.requestId)){
    approvals.retry();setUnconfirmed(false);approvalAttempt.current=null;
    toast('订单已保存，请在列表查看飞书审批状态');
    return true;
   }
  }
  if(clientError&&latest){approvalAttempt.current=null;setUnconfirmed(false);throw error}
  if(clientError&&error.status!==409){approvalAttempt.current=null;throw error}
  setUnconfirmed(true);
  throw Error('暂未确认订单提交结果。请在当前表单重试，系统会沿用本次订单编号，不会重复发起审批。');
 }
}
async function submit(e,status){
 e.preventDefault();
 const form=e.currentTarget.tagName==='FORM'?e.currentTarget:e.currentTarget.form;
 const submittedFields=form?new FormData(form):null;
 if(uploading||submittingRequest.current)return;
 if(!user.permissions.includes(modal==='payment'?'payment.write':'business.write'))return toast('当前账号只有查看权限');
 if(unconfirmed&&status==='草稿')return;
 submittingRequest.current=true;setSubmitting(true);setSubmitError('');
 try{
  const kind=modal;let entry={...draft};
  if(kind==='order'&&entry.status==='已取消')throw Error('已取消订单不能编辑');
  if(kind==='customer'||kind==='product'){
   if(kind==='customer'){const missing=firstMissingCustomerField(entry,formData.customers.find(customer=>customer.id===entry.id));if(missing)throw Error('请填写'+missing[1])}
   if(!entry.name.trim())throw Error('请填写名称');
   const key=kind==='customer'?'customers':'products';entry.id ||= recordId(kind==='customer'?'c':'p');
   await save(replaceBusinessFormRecord(formData,key,entry));
  }else if(kind==='payment'){
   throw Error('请从“登记收款”打开收款审批申请，审批通过后入账');
  }else{
   if(orderLocked&&!unconfirmed){
    const prior=formData.orders.find(order=>order.id===entry.id);
    if(!prior)throw Error('订单已变化，请重新打开订单');
    await save(replaceBusinessFormRecord(formData,'orders',{...prior,note:entry.note,attachments:entry.attachments,updated:stamp()}));
    setModal(null);toast('备注和附件已保存');return;
   }
   if(!entry.id)entry.type=page==='advance'?'预收款订单':'销售订单';
   if(!entry.customerId)throw Error('请选择客户，历史订单需重新确认客户关联');
   if(entry.type==='预收款订单'){
    const amountCents=moneyCents(entry.total);if(amountCents===null||amountCents<=0)throw Error('预收款金额须大于零且最多两位小数');
    if(!paymentMethods.includes(entry.method))throw Error('请选择已启用的收款方式');
    entry.total=amountCents/100;entry.items??=[];
    if(entry.items.length&&(entry.items.some(item=>!item.product||!(Number(item.qty)>0)||item.price===''||Number(item.price)<0)||orderTotal(entry.items)===null))throw Error('请完整填写选填产品的数量和销售价，或移除该行');
   }else{
    if(!entry.items.length||entry.items.some(x=>!x.product||!(Number(x.qty)>0)||x.price===''||Number(x.price)<0))throw Error('请完整填写产品、数量和销售价');
    entry.total=orderTotal(entry.items);if(entry.total===null)throw Error('数量最多六位小数，销售价最多两位小数，请检查金额与数量');
   }
   entry.id ||= approvalAttempt.current?.orderId||nextOrderNumber(formData.orders);
   entry.created ||= stamp();entry.updated=stamp();entry.paid ||= 0;
   const settings=approvalSettings(approvals.config,entry);
   if(!unconfirmed){
    const extra=approvalDraftValues(approvals.config,entry,submittedFields);
    if(Object.keys(extra).length)entry.approvalDraft={...entry.approvalDraft,...extra};
   }
   const external=(!orderLocked||unconfirmed)&&!status&&isApprovalOrder(entry)&&(settings?.required||settings?.enabled||!settings);
   if(external){
    const problem=submissionProblem(approvals.config,entry,approvals.error);if(problem)throw Error(problem);
    entry.status='审批中';
    const details=approvalAttempt.current?.body?.approval??approvalDetails(approvals.config,entry,submittedFields);
    if(await saveAndSubmitOrder(entry,details))setModal(null);
    return;
   }
   entry.status=orderLocked?draft.status:status||(draft.status==='已批准'?'已批准':'审批中');
   await save(replaceBusinessFormRecord(formData,'orders',entry));
  }
  setModal(null);toast(status==='草稿'?'草稿已保存':'保存成功');
 }catch(error){setSubmitError(error.message);toast(error.message)}
 finally{submittingRequest.current=false;setSubmitting(false)}
}
function orderFinance(order){
 if(order.status==='已取消')return {status:'已取消',tone:'warning',paid:Number(order.paid||0),outstanding:0};
 const shipment=jst.records[order.id];
 return salesOrderFinance(order,shipment?.effectiveOrder,shipment?.creditFulfillment===true);
}
async function pushCreditOrder(orderId,body){
 await api('jst/orders/'+encodeURIComponent(orderId)+'/credit-push',{...body,creditFulfillment:true,accountingMode:'ON_ACCOUNT_TENDER'});
 approvals.retry();
 try{const rows=await api('jst/shipments');rows.forEach(jst.update)}catch{toast('推送资料已保存，发货进度暂未刷新，请稍后查看');return}
 toast('账期推送资料已保存，请查看聚水潭处理进度');
}
async function retryCreditOrder(orderId){
 try{await api('jst/orders/'+encodeURIComponent(orderId)+'/credit-push/retry',{});approvals.retry();toast('正在核对原聚水潭单号和推送进度')}
 catch(error){toast(error.message)}
}
async function requestOrderApproval(order,action,body={}){
 try{const result=await approvals.request(order.id,action,body);if(result)toast(approvalLabels[result.status]||'审批状态已更新')}
 catch(error){toast(error.message)}
}
async function cancelOrder(order){
 if(!confirm('确认取消订单“'+orderNumber(order)+'”？正在进行的飞书审批会先撤回，确认后订单不再计入待回款。'))return;
 try{
  const result=await approvals.request(order.id,'cancel',{});
  if(result)toast(result.approval?.instanceCode?'订单已取消，飞书审批已撤回':'订单已取消');
 }catch(error){void refreshData();toast(error.message)}
}
async function deleteOrder(order){
 if(!confirm('确认删除订单“'+orderNumber(order)+'”？删除后该订单会从列表隐藏，历史记录保留。'))return;
 try{const result=await approvals.request(order.id,'delete',{});if(result)toast('订单已删除')}
 catch(error){void refreshData();toast(error.message)}
}
function actionsForOrder(order){
 const record=approvals.records[order.id],shipment=jst.records[order.id];
 const busy=Boolean(approvals.busy[order.id]||submitting||opening);
 const canWrite=user.permissions.includes('business.write');
 const canSettle=user.permissions.includes('payment.write');
 const canApprove=user.permissions.includes('order.approve');
 const lifecycle=canWrite&&Boolean(approvals.config)&&!approvals.error;
 const canceled=order.status==='已取消';
 const submission=canWrite&&!approvals.error&&canSubmitApproval(order,record,approvals.config);
 const link=approvalInstanceUrl(record?.instanceCode);
 return [
  {key:'details',label:'查看详情',disabled:busy,onSelect:()=>viewOrder(order)},
  canWrite&&!canceled&&order.source!=='jushuitan-settlement'&&{key:'edit',label:approvalLocksOrder(record)||order.status==='已批准'?'修改备注和附件':'编辑',disabled:busy,onSelect:()=>open('order',order)},
  submission&&{key:'submit',label:record?'重新发起飞书审批':'发起飞书审批',disabled:busy,onSelect:()=>isSettlementApprovalOrder(order)?viewOrder(order):open('order',order)},
  record&&{key:'sync',label:'同步审批状态',disabled:busy,onSelect:()=>requestOrderApproval(order,'sync')},
  canWrite&&record?.status==='UNKNOWN'&&record.canRetryOriginal&&record.originalVersion&&{key:'retry',label:'重试原审批',disabled:busy,onSelect:()=>requestOrderApproval(order,'submit',{retryOriginal:true,originalVersion:record.originalVersion})},
  link&&{key:'feishu',label:'在飞书查看',href:link,disabled:busy},
  page==='orders'&&order.type==='销售订单'&&order.source!=='jushuitan-settlement'&&order.status==='已批准'&&canApprove&&canWrite&&{key:'ship',label:shipment?'发货详情':'聚水潭推送资料',disabled:busy,onSelect:()=>shipment?withCurrentData(()=>setJstOrder(order)):viewOrder(order)},
  canWrite&&canApprove&&['FAILED','UNKNOWN','BLOCKED'].includes(record?.creditPush?.status)&&{key:'credit-retry',label:record.creditPush.status==='UNKNOWN'?'核对原聚水潭单':'重试账期推送',disabled:busy,onSelect:()=>retryCreditOrder(order.id)},
  ['销售订单','海外提货单'].includes(order.type)&&user.permissions.includes('business.read')&&{key:'documents',label:'关联单据',disabled:busy,onSelect:()=>withCurrentData(()=>setDocumentOrder(order))},
  !canceled&&order.status==='已批准'&&moneyCents(orderExecutionView(order,shipment).paid)<moneyCents(orderExecutionView(order,shipment).total)&&canSettle&&{key:'payment',label:receiptReservations.has(order.id)?'查看收款审批':'登记收款',disabled:busy,onSelect:()=>receiptReservations.has(order.id)?go('receipt-orders'):open('payment',order)},
  lifecycle&&canCancelOrder(order,record,shipment)&&{key:'cancel',label:record?.cancelPending?'核对并取消订单':'取消订单',disabled:busy,danger:true,onSelect:()=>cancelOrder(order)},
  lifecycle&&canDeleteOrder(order,record,shipment)&&{key:'delete',label:'删除订单',disabled:busy,danger:true,onSelect:()=>deleteOrder(order)},
 ].filter(Boolean);
}
async function deleteCustomer(customer){
 if(db.orders.some(o=>o.customerId===customer.id||(!o.customerId&&o.customer===customer.name))||Number(customer.balance||0)!==0)return toast('该客户有关联订单或预收款余额，不能删除');
 if(!confirm('确认删除客户“'+customer.name+'”？删除后无法恢复。'))return;
 try{await save({...db,customers:db.customers.filter(c=>c.id!==customer.id)});toast('客户已删除')}catch(e){toast(e.message)}
}
async function deleteProduct(product){
 if(db.orders.some(order=>order.items?.some(item=>item.product===product.id)))return toast('该商品已被订单使用，不能删除');
 if(!confirm('确认删除商品“'+product.name+'”？删除后无法恢复。'))return;
 try{await save({...db,products:db.products.filter(p=>p.id!==product.id)});toast('商品已删除')}catch(e){toast(e.message)}
}
async function upload(files,product=false){
 if(uploading)return;
 if(files.some(f=>!f.size||f.size>5*1024*1024))return toast('图片须大于零且不能超过5MB');
 if(!files.length)return;setUploading(true);
 try{const images=await Promise.all(files.map(uploadImage));if(product)patch('image',images[0]?.url||'');else setDraft(d=>({...d,attachments:[...(d.attachments||[]),...images]}))}catch(e){toast(e.message)}finally{setUploading(false)}
}
const catalogProducts=page==='products'?db.products.filter(matchesProductPrefix):[];
const normalizedQuery=query.trim().toLowerCase();
let rows=page==='customers'?db.customers:page==='products'?catalogProducts:listedOrders.filter(r=>page==='advance'?r.type==='预收款订单':salesMode?(salesOrderType==='all'||r.type===salesOrderType):true);rows=rows.filter(r=>(!normalizedQuery||JSON.stringify(r).toLowerCase().includes(normalizedQuery))&&(!filter||[r.status,r.owner,r.customer,r.channel].includes(filter)));if(page==='customers')rows=rows.filter(r=>matchesCustomerFilters(r,customerFilters));if(page==='orders')rows=rows.filter(r=>matchesOrderFilters(r,orderFilters));if(sort)rows=[...rows].reverse();
useEffect(()=>setListPage(1),[page,query,filter,sort,orderFilters,customerFilters,pageSize]);
const currentListPage=Math.min(listPage,Math.max(1,Math.ceil(rows.length/pageSize)));const visibleRows=rows.slice((currentListPage-1)*pageSize,currentListPage*pageSize);
function exportData(){const keys=page==='customers'?['name','entity','balance','owner','status','channel','contact','phone','wechat']:page==='products'?['code','name','category','unit','price']:['id','owner','type','status','customer','total','paid','note'];const csv=page==='customers'?customerExportCsv(rows):'\uFEFF'+[keys,...rows.map(r=>keys.map(k=>(k==='id'&&['orders','advance'].includes(page)?orderNumber(r):salesMode&&['total','paid'].includes(k)?jst.records[r.id]?.effectiveOrder?.[k]??r[k]:r[k])??''))].map(a=>a.map(csvCell).join(',')).join('\r\n');const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=title+'.csv';a.click();URL.revokeObjectURL(url)}
const fields=(label,key,options,type='text',required=false)=>options?
 <OrderSelect label={label} required={required} disabled={submitting||unconfirmed||(orderLocked&&key!=='note')} value={String(draft[key]||'')}
  onChange={value=>patch(key,value)} placeholder={'请选择'+label} searchPlaceholder={'搜索'+label}
  options={options.map(value=>({value,label:value==='预收款订单'?'预收款':value}))}/>:
 <label className="field"><span>{label}{required&&<em>*</em>}</span><input disabled={submitting||unconfirmed||(orderLocked&&key!=='note')} required={required} type={type} step={type==='number'?'any':undefined} min={type==='number'?0:undefined} value={draft[key]??''} placeholder={type==='number'?'请输入数字':'请输入文本'} onChange={e=>patch(key,e.target.value)}/></label>;
const originalCustomer=formData.customers.find(customer=>customer.id===draft.id);
const customerFields=(label,key,options,type='text')=>fields(label,key,options&&isLegacyCustomer(originalCustomer)&&draft[key]&&!options.includes(draft[key])?[draft[key],...options]:options,type,customerFieldRequired(originalCustomer,key));
const newButton=<button className="primary" disabled={opening||!user.permissions.includes('business.write')} onClick={()=>open(page==='customers'?'customer':page==='products'?'product':'order')}><Icon name="plus" size={17}/>{salesMode?'新建销售订单':'新建'}</button>;
const tools=<div className="toolbar-actions">{salesMode&&page==='orders'&&<select className="sales-order-type-select" aria-label="销售订单类型" value={salesOrderType} onChange={e=>{setSalesOrderType(e.target.value);setListPage(1)}}><option value="销售订单">销售订单</option><option value="海外提货单">海外提货单</option><option value="all">全部订单类型</option></select>}{isCustomerPage&&<button type="button" className="secondary" aria-label="刷新客户" disabled={refreshing||opening} onClick={()=>refreshData()}>{refreshing?'正在刷新…':'刷新客户'}</button>}<div className="search"><Icon name="search" size={17}/><input aria-label="搜索" placeholder="搜索" value={query} onChange={e=>search(e.target.value)}/></div><button title="筛选" aria-label="筛选" aria-expanded={showFilter} className="icon-btn" onClick={()=>toggleFilter(!showFilter)}><Icon name="filter"/></button><button title="切换排序" className="icon-btn" onClick={()=>setSort(!sort)}><Icon name="sort"/></button>{page!=='products'&&<><button title="导出CSV" className="icon-btn" onClick={exportData}><Icon name="export"/></button></>}</div>;
const money=n=>'¥ '+Number(n).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const summary=salesMode&&page==='orders'?[['订单数',listedOrders.filter(o=>salesOrderType==='all'||o.type===salesOrderType).length,'orders'],['待审批',listedOrders.filter(o=>(salesOrderType==='all'||o.type===salesOrderType)&&o.status==='审批中').length,'clock'],['订单总额',dashboardMoney(sumDashboardMoney(activeOrders.filter(o=>salesOrderType==='all'||o.type===salesOrderType).map(o=>jst.records[o.id]?.effectiveOrder?.total??o.total))),'cash']]:page==='products'?[['产品总数',catalogProducts.length,'products'],['产品分类',new Set(catalogProducts.map(p=>p.category).filter(Boolean)).size,'layers'],['已设置售价',catalogProducts.filter(p=>p.price!==''&&p.price!=null).length,'cash']]:page==='customers'?[['客户总数',db.customers.length,'customers'],['合作客户',db.customers.filter(c=>c.status==='达成合作').length,'check'],['预收款余额',money(db.customers.reduce((a,c)=>a+Number(c.balance||0),0)),'cash']]:page==='advance'?[['预收款',rows.length,'orders'],['预收款金额',money(rows.filter(r=>r.status!=='已取消').reduce((a,r)=>a+r.total,0)),'cash'],['已收金额',money(rows.reduce((a,r)=>a+r.paid,0)),'check']]:[['订单总数',listedOrders.length,'orders'],['待审批',listedOrders.filter(r=>r.status==='审批中').length,'clock'],['订单总额',money(activeOrders.reduce((a,r)=>a+Number(jst.records[r.id]?.effectiveOrder?.total??r.total),0)),'cash']];
const orderViewDisabled=Boolean(opening||uploading||submitting||unconfirmed||receiptAttempt||modal||detailsOrder||settlementOpen||receiptEntry||jstOrder||documentOrder||admin);
const OrderListPanel=page==='orders'?'section':React.Fragment;
const orderListPanelProps=page==='orders'?{className:'order-view-panel',role:'tabpanel',id:'order-panel-list','aria-labelledby':'order-view-list',tabIndex:0}:{};
if(loading)return <WorkspaceShell user={user} onLogout={onLogout} page={navigationPage} go={go} Icon={Icon}><div className="empty" role="status">正在加载数据…</div></WorkspaceShell>;
if(loadError)return <WorkspaceShell user={user} onLogout={onLogout} page={navigationPage} go={go} Icon={Icon}>
  <section className="auth-card">
    <h1>数据加载失败</h1>
    <p className="auth-error" role="alert">{loadError}</p>
    <button type="button" className="primary" onClick={retryLoad}>重试</button>
  </section>
</WorkspaceShell>;
return <><WorkspaceShell user={user} onLogout={onLogout} page={navigationPage} go={go} Icon={Icon} mainArea={mainArea}>{receiptAttempt&&!receiptEntry&&page!=='receipts'&&<div className="receipt-approval-notice receipt-approval-resume" role="status"><span>上次收款申请结果待核对，原申请编号和填写内容已保留。</span><button type="button" className="secondary" onClick={()=>withCurrentData(()=>setReceiptEntry(true))}>继续核对收款申请</button></div>}{page==='sys-payment-methods'?<PaymentMethods api={api} Icon={Icon} DialogLayer={DialogLayer}/>:isSystem?<SystemManagement page={page} api={api} Icon={Icon} DialogLayer={DialogLayer}/>:page==='receipts'?<ReceiptOrders api={api} db={db} Icon={Icon} save={save} DialogLayer={DialogLayer} paymentMethods={paymentMethods} approvals={receiptApprovals} canRegister={canRegisterReceipt} attempt={receiptAttempt} onAttemptChange={setReceiptAttempt} shipments={jst.records} onOpenOrder={id=>receiptAttempt?toast('请先完成或核对本次收款申请'):onOpenSalesOrder(id)}/>:<><div className="page-heading"><div><div className="breadcrumb">业务管理 <span>/</span> {title}</div><h1>{title}</h1><p>{({orders:salesMode?'集中查看销售订单、产品及当前回款进度。':'有序管理每一笔订单，让业务进展清晰可见。',advance:'统一管理客户预收款及收款记录。',customers:'沉淀客户信息，让每一次合作都有迹可循。',products:'商品资料和仓库库存以聚水潭为准，CRM只读使用。'})[page]}</p></div><div className="page-heading-symbol"><Icon name={page==='products'?'products':page==='customers'?'customers':'orders'} size={30}/></div></div>{page==='orders'&&<><OrderViewTabs view={orderItemsView?'items':'list'} onViewChange={changeOrderView} disabled={orderViewDisabled}/><section hidden role="tabpanel" id={orderItemsView?'order-panel-list':'order-panel-items'} aria-labelledby={orderItemsView?'order-view-list':'order-view-items'}/></>}{orderItemsView?<section className="order-view-panel" role="tabpanel" id="order-panel-items" aria-labelledby="order-view-items" tabIndex={0}><SalesItems embedded orders={db.orders} api={api} Icon={Icon} DialogLayer={DialogLayer} revision={db.revision} onOpenOrder={openItemOrder}/></section>:<OrderListPanel {...orderListPanelProps}><div className="summary-grid">{summary.map(([label,value,icon])=><div className="summary-card" key={label}><span className="summary-icon"><Icon name={icon} size={21}/></span><div><span className="summary-label">{label}</span><strong>{value}</strong></div></div>)}</div>{page==='orders'&&<OrderFilters orders={db.orders} customers={db.customers} members={memberNames} value={orderFilters} onChange={setOrderFilters} expanded={showFilter} onExpanded={toggleFilter} Icon={Icon}/>}
{['orders','advance'].includes(page)&&approvals.error&&<p className="system-error" role="alert">飞书审批状态读取失败：{approvals.error}<button type="button" onClick={approvals.retry}>重试</button></p>}
{page==='customers'&&<CustomerFilters customers={db.customers} members={memberNames} value={customerFilters} onChange={setCustomerFilters} expanded={showFilter} onExpanded={toggleFilter} Icon={Icon}/>}
{showFilter&&!['orders','customers'].includes(page)&&<div className="filter-panel"><span>筛选条件</span><select value={filter} onChange={e=>setFilter(e.target.value)}><option value="">全部记录</option>{[...new Set([...memberNames,...db.customers.map(c=>c.name),'审批中','已批准','草稿','达成合作','线下门店','其他'])].map(v=><option key={v}>{v}</option>)}</select><button className="link-btn" onClick={()=>{setFilter('');search('')}}>清除</button></div>}
{page==='products'?<JushuitanProducts Icon={Icon} DialogLayer={DialogLayer} db={db} api={api} onSynced={next=>setDatabase(previous=>Number(next.revision)>=Number(previous.revision)?next:previous)} canWrite={user.permissions.includes('business.write')}/>:<section className="panel"><div className={'table-toolbar business-table-toolbar'+(salesMode?' sales-order-toolbar':'')}><div className="toolbar-primary-actions">{newButton}{isCustomerPage&&user.permissions.includes('business.write')&&<button type="button" className="secondary" disabled={opening} onClick={()=>withCurrentData(()=>setCustomerImportOpen(true))}><Icon name="upload" size={16}/>导入客户</button>}{page==='orders'&&user.permissions.includes('business.write')&&<button type="button" className="secondary" disabled={opening} onClick={()=>withCurrentData(()=>setSettlementOpen(true))}>聚水潭结算补录</button>}</div>{tools}</div>
{isCustomerPage&&<div className="customer-list-results">
  <span aria-live="polite">共 {db.customers.length} 位客户，当前匹配 {rows.length} 位</span>
  {refreshError&&<span role="alert">刷新失败：{refreshError}，当前保留上次数据，请重试。</span>}
  {hasCustomerFilters&&<button type="button" className="link-btn" onClick={clearCustomerSearch}>清除搜索和筛选</button>}
</div>}
<div className="table-scroll"><table className={'data-table'+(isCustomerPage?' customer-table':'')+(salesMode&&page==='orders'?' sales-orders-table':'')}><thead><tr>{(page==='customers'?['客户名称','客户主体','客户预付款','负责人','客户状态','销售渠道','线上销售店铺&链接','联系人名称','联系人职位','联系人手机','联系人微信','操作']:page==='advance'?['订单编号','签单人','客户名称','订单类型','审批状态','备注','图片附件','预收款金额','创建时间','更新时间','操作']:['订单编号','签单人','订单类型','审批状态','客户名称','订单总额',...(salesMode?['回款状态','累计已收','待回款']:[]),'备注','图片附件','创建时间','更新时间','发货状态','操作']).map(h=><th key={h}>{page==='advance'?h.replaceAll('订单',''):h}</th>)}</tr></thead><tbody>{visibleRows.map(r=><tr key={r.id}>{page==='customers'?<><td title={r.name}>{r.name}{r.externalLinks?.length>0&&<small className="customer-identity-note" title={r.externalLinks.map(externalLinkLabel).join('；')}>{r.externalLinks.map(externalLinkLabel).join('；')}</small>}<LegacyCustomerNote customer={r}/></td><td title={r.entity||''}>{r.entity||'--'}</td><td>{money(r.balance)}</td><td><OwnerBadge name={r.owner}/></td><td><Badge>{r.status}</Badge></td><td><Badge tone={r.channel==='其他'?'lilac':'yellow'}>{r.channel}</Badge></td><td>{r.links||'--'}</td><td>{r.contact||'--'}</td><td>{r.position||'--'}</td><td>{r.phone||'--'}</td><td>{r.wechat||'--'}</td><td><button className="link-btn" disabled={opening} aria-busy={openingTarget==='customer:'+r.id} style={{minWidth:56}} onClick={()=>open('customer',r)}>{openingTarget==='customer:'+r.id?'打开中…':'编辑'}</button>{user.permissions.includes('business.write')&&<button className="link-btn" onClick={()=>deleteCustomer(r)}>删除</button>}</td></>:<><td><button type="button" className="order-id-link" aria-label={'查看订单 '+orderNumber(r)} disabled={opening} onClick={()=>viewOrder(r)}>{orderNumber(r)}</button></td><td><Person name={r.owner}/></td>{page==='advance'?<><td><Badge>{r.customer}</Badge></td><td><Badge tone="green">{r.type==='预收款订单'?'预收款':r.type}</Badge></td><td><ApprovalStatus order={r} record={approvals.records[r.id]} config={approvals.config} error={approvals.error} Badge={Badge}/></td></>:<><td><Badge tone={r.type==='预收款订单'?'green':'purple'}>{r.source==='jushuitan-settlement'?(r.settlement.cycle==='month'?'月结补录':'日结补录'):r.type}</Badge></td><td><ApprovalStatus order={r} record={approvals.records[r.id]} config={approvals.config} error={approvals.error} Badge={Badge}/></td><td><Badge>{r.customer}</Badge></td><td>¥ {r.total}{jst.records[r.id]?.effectiveOrder&&<small className="form-hint">当前执行 ¥ {Number(jst.records[r.id].effectiveOrder.total).toFixed(2)}</small>}</td>{salesMode&&<><td><Badge tone={orderFinance(r).tone}>{orderFinance(r).status}</Badge></td><td>{dashboardMoney(orderFinance(r).paid)}</td><td>{dashboardMoney(orderFinance(r).outstanding)}</td></>}</>}<td>{r.note||'--'}</td><td>{r.attachments?.length?r.attachments.map((a,i)=><BusinessImage key={i} link className="attachment" src={a.url} alt={a.name||"图片附件"} title={a.name}/>):'--'}</td>{<>{page==='advance'&&<td>¥ {r.total}{jst.records[r.id]?.effectiveOrder&&<small className="form-hint">当前执行 ¥ {Number(jst.records[r.id].effectiveOrder.total).toFixed(2)}</small>}</td>}<td>{r.created}</td><td>{r.updated}</td>{page==='orders'&&<td>{r.type==='销售订单'&&r.source!=='jushuitan-settlement'?(jst.error?'进度加载失败':(approvals.records[r.id]?.creditPush&&!jst.records[r.id]?({QUEUED:'待自动推送',BLOCKED:'待补充推送资料',SUBMITTING:'推送中',UNKNOWN:'原单待核对',FAILED:'推送失败',ACCEPTED:'聚水潭已接单'}[approvals.records[r.id].creditPush.status]||'待核对'):shipmentLabel(jst.records[r.id]))):'—'}</td>}</>}<td><OrderActionMenu orderId={r.id} orderNumber={orderNumber(r)} actions={actionsForOrder(r)} Icon={Icon} disabled={opening||submitting}/>
</td></>}</tr>)}</tbody></table>{!rows.length&&<div className="empty">暂无符合条件的记录</div>}</div><Pagination total={rows.length} page={currentListPage} pageSize={pageSize} onPage={setListPage} onPageSize={setPageSize} pageSizes={isCustomerPage?[5,10,20,50]:undefined}/></section>}</OrderListPanel>}</>}</WorkspaceShell>
<CustomerImportDialog open={customerImportOpen} onClose={()=>setCustomerImportOpen(false)} api={api} uploadFile={uploadCustomerFile} customers={formData.customers} owners={memberNames} environment={jst.config?.environment||'production'} DialogLayer={DialogLayer} Icon={Icon} onImported={result=>{setDatabase(previous=>Number(result.state.revision)>=Number(previous.revision)?result.state:previous);setCustomerImportOpen(false);clearCustomerSearch();toast(`客户导入完成：新增 ${result.summary.created}，关联 ${result.summary.linked}，跳过 ${result.summary.skipped}`)}}/>
{receiptEntry&&<ReceiptOrders entryOnly api={api} db={formData} Icon={Icon} save={save} DialogLayer={DialogLayer} paymentMethods={paymentMethods} approvals={receiptApprovals} canRegister={canRegisterReceipt} attempt={receiptAttempt} onAttemptChange={setReceiptAttempt} initialOrderId={typeof receiptEntry==='string'?receiptEntry:undefined} shipments={jst.records} onEntryClose={()=>setReceiptEntry(false)} onRegistered={record=>{setReceiptEntry(false);toast(record.direct?'收款已登记':record.status==='APPROVED'&&record.booked!==true?'收款审批已通过，入账待处理':'收款申请已记录：'+(receiptApprovalLabels[record.status]||'请查看审批状态'))}}/>}
<JushuitanSettlement open={settlementOpen} api={api} customers={formData.customers} approvals={approvals} DialogLayer={DialogLayer} Icon={Icon} onClose={()=>setSettlementOpen(false)} onCreated={result=>{const next=result.state;setDatabase(previous=>Number(next.revision)>=Number(previous.revision)?next:previous);if(result.approval)approvals.remember(result.approval);else approvals.retry();setSettlementOpen(false);toast('结算补录单已保存，'+(approvalLabels[result.approval?.status]||'请查看飞书审批状态'))}}/>
{detailsOrder&&<OrderDetails key={detailsOrder.id} order={db.orders.find(order=>order.id===detailsOrder.id)||detailsOrder} customer={db.customers.find(customer=>customer.id===detailsOrder.customerId)} products={db.products} approvals={approvals} shipment={jst.records[detailsOrder.id]} api={api} DialogLayer={DialogLayer} Badge={Badge} Icon={Icon} onClose={()=>setDetailsOrder(null)} canCreditPush={user.permissions.includes('business.write')&&user.permissions.includes('order.approve')} onCreditPush={body=>pushCreditOrder(detailsOrder.id,body)} onSubmitApproval={user.permissions.includes('business.write')?async order=>{const record=approvals.records[order.id];const result=await approvals.request(order.id,'submit',{orderUpdated:order.updated,...(record?.retryVersion?{retryVersion:record.retryVersion}:{})});if(result)toast(approvalLabels[result.status]||'飞书审批状态已更新')}:undefined}/>}
<JushuitanDocuments order={documentOrder} api={api} canSettle={user.permissions.includes('payment.write')} canWrite={user.permissions.includes('business.write')} Icon={Icon} DialogLayer={DialogLayer} onClose={()=>setDocumentOrder(null)}/>
<ShipmentDialog canSettle={user.permissions.includes('payment.write')} paymentMethods={paymentMethods} order={jstOrder} db={formData} api={api} record={jstOrder?jst.records[jstOrder.id]:null} config={jst.config} onUpdate={jst.update} onClose={()=>setJstOrder(null)} DialogLayer={DialogLayer} canWrite={user.permissions.includes('order.approve')&&user.permissions.includes('business.write')}/><DialogLayer open={Boolean(modal)} onClose={closeForm} kind={['order','payment'].includes(modal)?'drawer':'modal'}>{modal&&<form aria-busy={submitting} onSubmit={submit} onClick={e=>e.stopPropagation()} className={['order','payment'].includes(modal)?'drawer':'modal'}><div className="modal-title"><h2>{modal==='order'?(page==='advance'?(draft.id?'编辑预收款':'新建预收款'):(draft.id?'编辑订单':'新建订单')):modal==='payment'?'编辑回款信息':modal==='customer'?(draft.id?'编辑客户':'新建客户'):draft.id?'编辑记录':'创建记录'}</h2><button type="button" disabled={uploading||submitting||unconfirmed} onClick={closeForm} aria-label="关闭"><Icon name="close" size={18}/></button></div><div className="modal-body">{orderLocked&&!unconfirmed&&<p className="approval-lock-note">订单已提交审批，核心信息已锁定，可补充备注和附件。修改备注和附件不会更新已提交的飞书审批内容。</p>}{['order','payment'].includes(modal)&&draft.id&&<OrderChangeLog key={draft.id} orderId={draft.id} orderNumber={orderNumber(draft)} api={api}/>} {modal==='customer'?<><LegacyCustomerSource customer={draft}/>{customerFields('客户名称','name')}{customerFields('客户主体','entity')}<div className="read-field">客户预付款<p>{money(draft.balance)}</p><small>{isLegacyCustomer(draft)?'根据期初预收款、预收款和核销记录自动计算。':'根据预收款和核销记录自动计算。'}</small></div>{customerFields('负责人','owner',memberNames)}{customerFields('客户状态','status',['达成合作','跟进中','潜在客户','暂停合作'])}{customerFields('销售渠道','channel',['线下门店','淘宝店铺','其他','电商平台'])}<label className="field"><span>线上销售店铺&链接</span><div className="rich-toolbar">店铺名称与链接</div><textarea aria-label="线上销售店铺和链接" value={draft.links||''} onChange={e=>patch('links',e.target.value)} placeholder="请输入文本"/></label>{customerFields('联系人名称','contact')}{fields('联系人职位','position')}{customerFields('联系人手机','phone',null,'tel')}{fields('联系人微信','wechat')}<CustomerExternalLinks value={draft.externalLinks||[]} onChange={value=>patch('externalLinks',value)} disabled={!user.permissions.includes('business.write')} environment={jst.config?.environment||'production'}/></>:modal==='product'?<>{fields('产品长代码','code')}{fields('聚水潭 SKU 编码','jstSku')}{fields('产品名称','name',null,'text',true)}{fields('产品类型','category',['配件类','主机类','数码类','家电类','其他'])}{fields('单位','unit',['个','件','套','箱'])}<label className="field"><span>产品图片</span><FileDropZone label="上传产品图片" disabled={uploading||submitting||unconfirmed} accept="image/png,image/jpeg,image/gif,image/webp" onFiles={files=>upload(files,true)}>{draft.image&&<BusinessImage className="drop-image-preview" src={draft.image} alt="产品图片"/>}</FileDropZone><small>请上传 0MB-5MB 的图片</small></label>{fields('销售价','price',null,'number')}{fields('规格型号','model')}{fields('成本价','cost',null,'number')}</>:<>{modal==='payment'?<><div className="read-field">订单编号<p>{orderNumber(draft)}</p></div><div className="read-field">签单人<p><Person name={draft.owner}/></p></div><div className="read-field">客户名称<p><Badge>{draft.customer}</Badge></p></div></>:<>{draft.id?fields(page==='advance'?'类型':'订单类型','type',['销售订单','预收款订单','海外提货单'],'text',true):<div className="read-field">{page==='advance'?'类型':'订单类型'}<p>{page==='advance'?'预收款':'销售订单'}</p></div>}{fields('签单人','owner',memberNames,'text',true)}<div>{draft.id&&!draft.customerId&&<p className="form-hint">历史客户：{draft.customer||'未填写'}。请重新选择客户以确认归属。</p>}<OrderSelect disabled={submitting||orderLocked} label="客户名称" required placeholder="请选择客户" searchPlaceholder="搜索客户名称、主体、联系人或电话" options={customerOptions(formData.customers).map(option=>({...option,detail:formData.customers.find(customer=>customer.id===option.value)?.entity||''}))} value={draft.customerId||''} onChange={id=>patch('customerId',id)}/></div></>}{modal==='order'&&isSalesApprovalOrder(draft)&&<OrderWarehousePicker api={api} order={draft} owner={draft.owner} value={draft.approvalDraft} disabled={submitting||orderLocked} onChange={value=>patch('approvalDraft',value)}/>}{(modal==='order'||draft.type!=='预收款订单')&&<><div className="field"><div className="order-editor-products-tools"><span>{modal==='payment'?'产品明细':draft.type==='预收款订单'?'产品明细（选填）':'订单产品明细'} {draft.type!=='预收款订单'&&<em>*</em>}</span>{modal==='order'&&<button type="button" className="link-btn" disabled={submitting||orderLocked} onClick={()=>setBulkProducts(true)}>添加产品明细</button>}</div><div className="line-items"><div className="line-head"><span></span><span>产品名称</span><span>规格型号</span><span>数量</span><span>销售价</span><span>总金额</span></div>{draft.items.map((item,i)=><div className="line-row" key={i}><span className="row-index">{i+1}</span>{modal==='payment'?<span>{formData.products.find(p=>p.id===item.product)?.name||'--'}</span>:<button type="button" disabled={submitting||orderLocked} className="payment-picker-trigger product-batch-trigger" aria-label={'选择产品（第'+(i+1)+'行）'} title="添加商品" onClick={()=>setBulkProducts(true)}><span>{formData.products.find(p=>p.id===item.product)?.name||'请选择产品'}</span><Icon name="chevron" size={14}/></button>}<span>{formData.products.find(p=>p.id===item.product)?.model||'--'}</span>{['qty','price'].map(k=>modal==='payment'?<span key={k}>{item[k]}</span>:<input disabled={submitting||orderLocked} key={k} aria-label={k==='qty'?'数量':'销售价'} type="number" min={k==='qty'?0.000001:0} step="any" value={item[k]} onChange={e=>patch('items',draft.items.map((x,n)=>n===i?{...x,[k]:e.target.value}:x))}/>)}<span className="line-total" aria-label={'总金额（第'+(i+1)+'行）'}>{orderTotal([item])===null?'—':'¥ '+orderTotal([item]).toFixed(2)}</span>{modal!=='payment'&&(draft.type==='预收款订单'||draft.items.length>1)&&<button type="button" className="remove-line" disabled={submitting||orderLocked} onClick={()=>patch('items',draft.items.filter((_,n)=>n!==i))} aria-label={'移除产品明细（第'+(i+1)+'行）'}><Icon name="close" size={18}/></button>}</div>)}{!draft.items.length&&<p className="form-hint">暂无产品明细，可点击“添加产品明细”选择商品。</p>}</div></div><div className="read-field">{draft.type==='预收款订单'?'商品明细合计':'订单总额'}<p>¥ {modal==='payment'?draft.total:(orderTotal(draft.items)??0).toFixed(2)}</p>{draft.type==='预收款订单'&&<small>预收款金额独立填写，添加商品不会改变预收款金额。</small>}</div></>}{modal==='payment'&&<><div className="read-field">回款状态<p><Badge tone="warning">{draft.paid?'部分回款':'待回款'}</Badge></p></div><p>已收款 ¥ {draft.paid}　待收款 ¥ {(draft.total-draft.paid).toFixed(2)}</p>{fields('本次收款金额','payment',null,'number',true)}{<div><OrderSelect label="收款方式" required disabled={submitting} placeholder="请选择收款方式" options={[...paymentMethods,...(draft.type==='预收款订单'||draft.source==='jushuitan-settlement'?[]:['预收款核销'])].map(value=>({value,label:value}))} value={draft.method||''} onChange={value=>patch('method',value)}/></div>}</>}{modal==='payment'&&draft.source==='jushuitan-settlement'&&fields('实际到账日期','receivedOn',null,'date',true)}{fields('备注','note')}<div className="field"><span>图片附件</span><FileDropZone label="上传图片附件" disabled={uploading||submitting||unconfirmed} accept="image/png,image/jpeg,image/gif,image/webp" multiple onFiles={upload}/><div className="attachment-list">{draft.attachments?.map((a,i)=><span key={i}><BusinessImage src={a.url} alt={a.name||"图片附件"}/><button type="button" disabled={submitting||unconfirmed} onClick={()=>patch('attachments',draft.attachments.filter((_,n)=>n!==i))} aria-label="关闭"><Icon name="close" size={18}/></button></span>)}</div></div>{modal==='order'&&draft.type==='预收款订单'&&<><div><OrderSelect disabled={submitting||orderLocked} label="收款方式" required placeholder="请选择收款方式" options={paymentMethods.map(value=>({value,label:value}))} value={draft.method||''} onChange={value=>patch('method',value)}/></div>{fields('预收款金额','total',null,'number',true)}</>}{modal==='order'&&<ApprovalFormFields key={'fields-'+(draft.id||'new')} order={draft} config={approvals.config} disabled={submitting||orderLocked} customer={formData.customers.find(customer=>customer.id===draft.customerId)} products={formData.products} onChange={value=>patch('approvalDraft',value)} onDiscountChange={(index,value)=>patch('items',draft.items.map((item,position)=>position===index?{...item,approvalDiscount:value}:item))}/>} {modal==='order'&&<ApprovalFlow key={draft.id||'new'} order={draft} approvals={approvals} api={api} Badge={Badge}/>}</>}{submitError&&<p className="system-error approval-submit-error" role="alert">{submitError}</p>}</div><div className="modal-footer"><button type="button" className="secondary" disabled={uploading||submitting||unconfirmed} onClick={closeForm}>取消</button>{modal==='order'&&!orderLocked&&user.permissions.includes('business.write')&&<button type="button" className="secondary" disabled={uploading||submitting||unconfirmed} onClick={e=>submit(e,'草稿')}>保存草稿</button>}<button className="primary" type="submit" disabled={uploading||submitting||Boolean(orderSubmissionProblem)||!user.permissions.includes(modal==='payment'?'payment.write':'business.write')}>{submitting?'正在提交…':uploading?'图片上传中…':unconfirmed?'重试本次提交':modal==='order'&&orderLocked?'保存备注和附件':modal==='order'&&isApprovalOrder(draft)?'提交飞书审批':'提交'}</button></div></form>}</DialogLayer><BulkProductPicker open={bulkProducts&&modal==='order'} onClose={()=>setBulkProducts(false)} onConfirm={ids=>{patch('items',addOrderProducts(draft.items,formData.products,ids));setBulkProducts(false)}} products={formData.products.filter(p=>p.source==='jushuitan'&&p.enabled&&!p.historicalOnly)} DialogLayer={DialogLayer} Icon={Icon}/><DialogLayer open={admin} onClose={()=>setAdmin(false)}><div className="management" onClick={e=>e.stopPropagation()}><div className="modal-title"><h2>本地数据管理</h2><button onClick={()=>setAdmin(false)} aria-label="关闭"><Icon name="close" size={18}/></button></div><div className="modal-body"><div className="management-actions">{user.role==='管理员'&&<><button className="secondary" onClick={async()=>{try{const backup=await api('backup');const url=URL.createObjectURL(new Blob([JSON.stringify(backup,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='亿觅CRM备份-'+new Date().toISOString().slice(0,10)+'.json';a.click();URL.revokeObjectURL(url)}catch(e){toast(e.message)}}}>备份全部业务数据</button><FileDropZone label="恢复备份" accept="application/json,.json" onFiles={async files=>{try{const backup=JSON.parse(await files[0].text());if(!confirm('恢复会替换当前全部业务数据和收款流水。请先备份当前数据。确认恢复？'))return;await api('restore',backup);location.reload()}catch(e){toast(e.message)}}}/></>}</div><h3>收款流水</h3><div className="table-scroll"><table className="ledger"><thead><tr>{['时间','订单','客户','金额','方式','操作人'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{receipts.map(r=><tr key={r.id}><td>{new Date(r.time).toLocaleString()}</td><td>{r.orderNumber||orderNumberForId(r.orderId||r.order,db.orders)}</td><td>{r.customer}</td><td>¥ {r.amount}</td><td>{r.method}</td><td>{r.operator}</td></tr>)}</tbody></table>{!receipts.length&&<p>暂无收款流水</p>}</div><h3>操作日志</h3>{logs.map(r=><p key={r.time+r.action}>{new Date(r.time).toLocaleString()} · {r.user} · {r.action}</p>)}</div></div></DialogLayer>{message&&<div className="toast">✓ {message}</div>}</>}
createRoot(document.getElementById('root')).render(<AuthBoundary>{(user,onLogout)=><App user={user} onLogout={onLogout}/>}</AuthBoundary>);
