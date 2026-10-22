import React,{useEffect,useRef,useState} from 'react';
import {api} from './authApi';
import {createAuthInitializer,feishuLoginUrl,loadAuthentication} from './authInitialization';
export default function AuthBoundary({children}){
 const[user,setUser]=useState(null),[feishu,setFeishu]=useState(null),[error,setError]=useState(''),[checking,setChecking]=useState(true),[starting,setStarting]=useState(false);
 const initializer=useRef(null),generation=useRef(0);
 if(!initializer.current)initializer.current=createAuthInitializer({api,storage:sessionStorage,location:window.location,history:window.history});
 async function load(){
  const request=++generation.current;setChecking(true);setError('');
  try{const result=await loadAuthentication({api,storage:sessionStorage});if(request===generation.current){setFeishu(result.feishu);setUser(result.user)}}
  catch(e){if(request===generation.current)setError(e.message)}finally{if(request===generation.current)setChecking(false)}
 }
 useEffect(()=>{
  let active=true;
  const expired=()=>{generation.current++;setUser(null);setChecking(false);setError('登录已过期，请重新使用飞书登录')};
  const restored=()=>setStarting(false);
  window.addEventListener('crm-auth-expired',expired);window.addEventListener('pageshow',restored);
  initializer.current().then(result=>{if(active){setUser(result.user);setFeishu(result.feishu);setError(result.error);setChecking(false)}});
  return()=>{active=false;generation.current++;window.removeEventListener('crm-auth-expired',expired);window.removeEventListener('pageshow',restored)};
 },[]);
 function startLogin(){
  if(starting||!feishu?.enabled)return;
  setStarting(true);setError('');
  location.href=feishuLoginUrl(feishu.redirectUri,location.origin);
 }
 async function logout(){try{await api('logout',{})}finally{sessionStorage.removeItem('crm-token');setUser(null);setError('')}}
 if(checking)return <div className="login-screen">正在检查登录状态…</div>;
 if(user&&!user.pending)return children(user,logout);
 if(user?.pending)return <main className="login-screen"><section className="auth-card"><div className="auth-brand">亿觅 CRM</div><h1>等待管理员授权</h1><p>{user.displayName}，你已通过飞书验证。请联系管理员在用户管理中为你分配角色。</p>{error&&<p role="alert" className="auth-error">{error}</p>}<button className="primary" onClick={load}>刷新授权状态</button><button className="secondary" onClick={logout}>退出登录</button></section></main>;
 return <main className="login-screen"><section className="auth-card"><div className="auth-brand">亿觅 CRM</div><h1>登录工作台</h1><p>使用企业飞书账号登录。</p>{error&&<p className="auth-error" role="alert">{error}</p>}<button type="button" className="primary feishu-login" disabled={!feishu?.enabled||starting} onClick={startLogin}>{starting?'正在前往飞书…':'飞书登录'}</button>{!feishu?.enabled&&<p className="form-hint">飞书登录尚未配置，请联系管理员。</p>}</section></main>;
}
