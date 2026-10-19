export const emptyOrderFilters=()=>({customer:'',status:'',owner:'',updatedFrom:'',updatedTo:''});
export function orderDate(value){
 if(!value)return '';
 if(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)){
  const date=new Date(value);
  return Number.isNaN(date.getTime())?'':date.toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'});
 }
 return /^\d{4}-\d{2}-\d{2}/.test(value)?value.slice(0,10):'';
}
export function matchesOrderFilters(order,filters){
 if(filters.customer&&order.customer!==filters.customer)return false;
 if(filters.status&&order.status!==filters.status)return false;
 if(filters.owner&&order.owner!==filters.owner)return false;
 const date=orderDate(order.updated);
 if((filters.updatedFrom||filters.updatedTo)&&!date)return false;
 return (!filters.updatedFrom||date>=filters.updatedFrom)&&(!filters.updatedTo||date<=filters.updatedTo);
}
