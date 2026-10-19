export function addOrderProducts(items,products,selected){
 const next=items.filter(item=>item.product||item.qty!==''||item.price!=='').map(item=>({...item}));
 for(const product of products.filter(p=>selected.includes(p.id))){
  const existing=next.find(item=>item.product===product.id);
  if(!existing)next.push({product:product.id,qty:'',price:product.price??''});
 }
 return next;
}
