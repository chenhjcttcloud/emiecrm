import React,{useEffect,useState} from 'react';
import {api} from './authApi';
export default function AuthBoundary({children}){
 const[user,setUser]=useState(null),[feishu,setFeishu]=useState(null),[error,setError]=useState(''),[checking,setChecking]=useState(true);
 async function load(){
  setChecking(true);
  try{
   const s=await api('status');
   const cfg=await api('auth/feishu/config');setFeishu(cfg);
   if(sessionStorage.getItem('crm-token')||!s.loginRequired)setUser(await api('me'));
   else setUser(null);
  }catch(e){setError(e.message)}finally{setChecking(false)}
 }
 useEffect(()=>{
  const expired=()=>setUser(null);window.addEventListener('crm-auth-expired',expired);
  async function init(){
   const url=new URL(location.href),ticket=url.searchParams.get('sso_ticket'),message=url.searchParams.get('sso_error');
   url.searchParams.delete('sso_ticket');url.searchParams.delete('sso_error');history.replaceState(null,'',url.pathname+url.search+url.hash);
   if(message)setError(message);
   if(ticket){try{const result=await api('auth/feishu/exchange',{ticket});sessionStorage.setItem('crm-token',result.token)}catch(e){setError(e.message)}}
   await load();
  }
  init();return()=>window.removeEventListener('crm-auth-expired',expired);
 },[]);
 async function logout(){try{await api('logout',{})}finally{sessionStorage.removeItem('crm-token');setUser(null)}}
 if(checking)return <div className="login-screen">正在检查登录状态…</div>;
 if(user&&!user.pending)return children(user,logout);
 if(user?.pending)return <main className="login-screen"><section className="auth-card"><div className="auth-brand">亿觅 CRM</div><h1>等待管理员授权</h1><p>{user.displayName}，你已通过飞书验证。请联系管理员在用户管理中为你分配角色。</p>{error&&<p role="alert" className="auth-error">{error}</p>}<button className="primary" onClick={load}>刷新授权状态</button><button className="secondary" onClick={logout}>退出登录</button></section></main>;
 return <main className="login-screen"><section className="auth-card"><div className="auth-brand">亿觅 CRM</div><h1>登录工作台</h1><p>使用企业飞书账号登录。</p>{error&&<p className="auth-error" role="alert">{error}</p>}<button type="button" className="primary feishu-login" disabled={!feishu?.enabled} onClick={()=>{location.href='/api/auth/feishu/start'}}>飞书登录</button>{!feishu?.enabled&&<p className="form-hint">飞书登录尚未配置，请联系管理员。</p>}</section></main>;
}
