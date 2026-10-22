export function feishuLoginUrl(redirectUri, currentOrigin){
 const origin=new URL(redirectUri,currentOrigin).origin;
 return new URL('/api/auth/feishu/start',origin).href;
}

export async function loadAuthentication({api,storage}){
 const[status,feishu]=await Promise.all([api('status'),api('auth/feishu/config')]);
 let user=null;
 if(storage.getItem('crm-token')||!status.loginRequired){
  try{user=await api('me')}catch(error){if(error.status!==401)throw error}
 }
 return {user,feishu};
}

// The same mounted boundary can run its effect again; exchange a single-use ticket only once.
export function createAuthInitializer({api,storage,location,history}){
 let initialization;
 return ()=>initialization??= (async()=>{
  const url=new URL(location.href),ticket=url.searchParams.get('sso_ticket');
  let error=url.searchParams.get('sso_error')||'';
  url.searchParams.delete('sso_ticket');url.searchParams.delete('sso_error');
  history.replaceState(null,'',url.pathname+url.search+url.hash);
  if(ticket){
   storage.removeItem('crm-token');
   try{const result=await api('auth/feishu/exchange',{ticket});storage.setItem('crm-token',result.token)}catch(e){error=e.message}
  }
  try{return {...await loadAuthentication({api,storage}),error}}
  catch(e){return {user:null,feishu:null,error:error||e.message}}
 })();
}
