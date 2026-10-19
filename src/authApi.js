export async function api(path,body){
 const token=sessionStorage.getItem('crm-token');
 const response=await fetch('/api/'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body?JSON.stringify(body):undefined});
 const result=await response.json();
 if(response.status===401){sessionStorage.removeItem('crm-token');window.dispatchEvent(new Event('crm-auth-expired'))}
 if(!response.ok)throw Error(result.error||'请求失败');
 return result;
}
