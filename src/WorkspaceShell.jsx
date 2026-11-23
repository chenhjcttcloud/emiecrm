import React,{useEffect,useRef,useState} from 'react';
import './workspace.css';

export default function WorkspaceShell({user,onLogout,page,go,Icon,mainArea,children}){
  const permissions=user.permissions;
  const canView=permissions.includes('dashboard.read');
  const canTargets=permissions.includes('dashboard.targets.manage');
  const dashboard=page.startsWith('dashboard-');
  const system=page.startsWith('sys-');
  const sales=page.startsWith('sales-')||['orders','receipts'].includes(page);
  const payments=['advance','receipt-orders'].includes(page);
  const[dashboardExpanded,setDashboardExpanded]=useState(dashboard);
  const[salesExpanded,setSalesExpanded]=useState(sales);
  const[systemExpanded,setSystemExpanded]=useState(system);
  const[paymentsExpanded,setPaymentsExpanded]=useState(payments);
  const localArea=useRef(null),area=mainArea||localArea;
  useEffect(()=>{
    if(dashboard)setDashboardExpanded(true);
    if(system)setSystemExpanded(true);
    if(sales)setSalesExpanded(true);
    if(payments)setPaymentsExpanded(true);
    area.current?.scrollTo({top:0,left:0,behavior:'instant'});
  },[page]);
  const caret=expanded=><span className="nav-caret"><Icon name="chevron" style={{transform:expanded?'rotate(180deg)':undefined}}/></span>;
  return <div className="app"><header className="topbar">
    <div className="brand"><span className="brand-mark"><Icon name="layers" size={22}/></span><span>亿觅 CRM</span></div>
    <div className="header-end"><span className="current-user"><span className="status-dot"/><span title={user.displayName||user.name}>{user.displayName||user.name}</span></span><button type="button" className="logout-button" onClick={onLogout}><Icon name="logout" size={16}/>退出登录</button></div>
  </header><div className="shell"><nav className="sidebar" aria-label="主导航">
    {(canView||canTargets)&&<div className="workspace-module">
      <div className="nav-section-label">经营分析</div>
      <button type="button" title="经营看板" aria-expanded={dashboardExpanded} className={'nav-item '+(dashboard?'active-parent':'')} onClick={()=>{setDashboardExpanded(!dashboardExpanded);if(!dashboardExpanded)go(canView?'dashboard-sales':'dashboard-targets')}}><Icon name="chart"/><span>经营看板</span>{caret(dashboardExpanded)}</button>
      {dashboardExpanded&&<div className="nav-sub">
        {canView&&<button type="button" aria-current={page==='dashboard-sales'?'page':undefined} className={page==='dashboard-sales'?'active':''} onClick={()=>go('dashboard-sales')}>销售看板</button>}
        {canTargets&&<button type="button" aria-current={page==='dashboard-targets'?'page':undefined} className={page==='dashboard-targets'?'active':''} onClick={()=>go('dashboard-targets')}>业绩目标</button>}
      </div>}
    </div>}
    {permissions.includes('business.read')&&<>
      <div className="workspace-module">
        <div className="nav-section-label">销售管理</div>
        <button type="button" title="销售模块" aria-expanded={salesExpanded} className={'nav-item '+(sales?'active-parent':'')} onClick={()=>{setSalesExpanded(!salesExpanded);if(!salesExpanded)go('sales-orders')}}><Icon name="orders"/><span>销售</span>{caret(salesExpanded)}</button>
        {salesExpanded&&<div className="nav-sub">{[['sales-orders','销售订单信息'],['sales-items','订单产品明细'],['sales-receipts','订单收款明细'],['sales-targets','销售业绩目标']].map(([key,label])=><button type="button" key={key} aria-current={page===key?'page':undefined} className={page===key?'active':''} onClick={()=>go(key)}>{label}</button>)}</div>}
      </div>
      <div className="nav-section-label">业务管理</div>
      <button type="button" title="收款管理" aria-expanded={paymentsExpanded} className={'nav-item '+(payments?'active-parent':'')} onClick={()=>setPaymentsExpanded(!paymentsExpanded)}><Icon name="cash"/><span>收款管理</span>{caret(paymentsExpanded)}</button>
      {paymentsExpanded&&<div className="nav-sub">{[['advance','预收款'],['receipt-orders','收款']].map(([key,label])=><button type="button" key={key} aria-current={page===key?'page':undefined} className={page===key?'active':''} onClick={()=>go(key)}>{label}</button>)}</div>}
      <button type="button" title="客户管理" className={'nav-item '+(page==='customers'?'active':'')} onClick={()=>go('customers')}><Icon name="customers"/><span>客户管理</span></button>
      <button type="button" title="产品管理" className={'nav-item '+(page==='products'?'active':'')} onClick={()=>go('products')}><Icon name="products"/><span>产品管理</span></button>
    </>}
    {permissions.includes('system.manage')&&<>
      <div className="nav-section-label system-section-label">系统设置</div>
      <button type="button" title="系统管理" aria-expanded={systemExpanded} className={'nav-item '+(system?'active-parent':'')} onClick={()=>{setSystemExpanded(!systemExpanded);if(!systemExpanded)go('sys-users')}}><Icon name="settings"/><span>系统管理</span>{caret(systemExpanded)}</button>
      {systemExpanded&&<div className="nav-sub">{[['sys-users','用户管理'],['sys-roles','角色权限'],['sys-departments','部门管理'],['sys-audit','操作日志'],['sys-payment-methods','收款方式'],['sys-backup','数据备份']].map(([key,label])=><button type="button" className={page===key?'active':''} key={key} onClick={()=>go(key)}>{label}</button>)}</div>}
    </>}
  </nav><main className="main" ref={area}>{children}</main></div></div>;
}
