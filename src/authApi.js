// A delayed response from an older request must not invalidate a newer login.
export async function authorizedFetch(url,options={}){
 const token=sessionStorage.getItem('crm-token');
 const response=await fetch(url,{...options,headers:{...options.headers,...(token?{Authorization:'Bearer '+token}:{})}});
 if(response.status===401&&token&&sessionStorage.getItem('crm-token')===token){
  sessionStorage.removeItem('crm-token');window.dispatchEvent(new Event('crm-auth-expired'));
 }
 return response;
}

async function jsonResponse(response){
 let result;
 try{result=await response.json()}catch{
  const error=Error(response.ok?'服务器返回了无效数据，请稍后重试':`请求失败（HTTP ${response.status}）`);error.status=response.status;throw error;
 }
 if(!response.ok){const error=Error(result?.error||'请求失败');error.status=response.status;throw error}
 return result;
}

export async function api(path,body){
 return jsonResponse(await authorizedFetch('/api/'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined}));
}

export async function uploadImage(file){
 const result=await jsonResponse(await authorizedFetch('/api/assets',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file}));
 return {name:file.name,url:result.url};
}
