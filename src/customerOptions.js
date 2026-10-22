export function customerOptions(customers){
 const counts=new Map();for(const customer of customers)counts.set(customer.name,(counts.get(customer.name)||0)+1);
 return customers.map(customer=>({value:customer.id,label:counts.get(customer.name)>1?`${customer.name}（${customer.entity||customer.contact||'客户'} · ${customer.id.slice(-6)}）`:customer.name,keywords:[customer.entity,customer.contact,customer.phone].filter(Boolean).join(' ')}));
}
