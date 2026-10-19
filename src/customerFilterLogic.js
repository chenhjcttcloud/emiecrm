export const emptyCustomerFilters=()=>({name:'',entity:'',owner:'',status:'',channel:'',prepayment:''});
export function matchesCustomerFilters(customer,filters){
 for(const key of ['name','entity'])if(filters[key]&&!String(customer[key]||'').toLowerCase().includes(filters[key].trim().toLowerCase()))return false;
 for(const key of ['owner','status','channel'])if(filters[key]&&customer[key]!==filters[key])return false;
 const balance=Number(customer.balance||0);
 return (!filters.prepayment||filters.prepayment==='positive'&&balance>0||filters.prepayment==='zero'&&balance===0);
}
