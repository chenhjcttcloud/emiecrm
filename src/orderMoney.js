// Sum decimal products exactly, then round the total to cents once (same as the backend).
export function orderTotal(items){
 let micros=0n;
 for(const item of items){
  const qty=String(item.qty??''),price=String(item.price??'');
  if(!/^\d+(?:\.\d{1,6})?$/.test(qty)||!/^\d+(?:\.\d{1,2})?$/.test(price)||qty.length>20||price.length>20)return null;
  const scaled=(value,digits)=>{const[whole,fraction='']=value.split('.');return BigInt(whole)*10n**BigInt(digits)+BigInt(fraction.padEnd(digits,'0'))};
  const quantity=scaled(qty,6),cents=scaled(price,2);
  if(quantity<=0n||quantity>1000000000000000n||cents>99999999999999n)return null;
  micros+=quantity*cents;
 }
 const cents=(micros+500000n)/1000000n;
 return cents>99999999999999n?null:Number(cents)/100;
}

export function moneyCents(value){
 const text=String(value??'');
 if(!/^\d+(?:\.\d{1,2})?$/.test(text)||text.length>20)return null;
 const[whole,fraction='']=text.split('.');const cents=BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));
 return cents>99999999999999n?null:Number(cents);
}
