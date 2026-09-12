import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const headers={"content-type":"application/json","cache-control":"no-store","x-content-type-options":"nosniff"};
async function sha256(v:string){const d=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(v));return Array.from(new Uint8Array(d)).map(b=>b.toString(16).padStart(2,"0")).join("");}
function secureEqual(a:string,b:string){if(a.length!==b.length)return false;let d=0;for(let i=0;i<a.length;i++)d|=a.charCodeAt(i)^b.charCodeAt(i);return d===0;}
function cleanSymbol(v:unknown){return String(v||"").trim().toUpperCase().replace(/[^A-Z0-9]/g,"").slice(0,30);}

Deno.serve(async(req:Request)=>{
 if(req.method!=="POST")return new Response(JSON.stringify({error:"method_not_allowed"}),{status:405,headers});
 try{
  const body=await req.json(); const action=String(body?.action||"list").trim().toLowerCase();
  const sb=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{persistSession:false}});
  const expected=String(Deno.env.get("SUPABASE_MCP_TOKEN")||""); const supplied=String(req.headers.get("x-chk-token")||""); const serverOk=!!expected&&secureEqual(expected,supplied);
  const appProjection="id,exchange,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,rationale,confidence,source,status,expires_at,bybit_order_id,result,created_at,updated_at,executed_at,processing_started_at,processing_owner";
  const serverProjection=`${appProjection},account_fingerprint`;

  if(action==="server_create_proposal"){
   if(!serverOk)return new Response(JSON.stringify({error:"unauthorized"}),{status:401,headers});
   const fp=String(body?.accountFingerprint||"").trim(); const symbol=cleanSymbol(body?.symbol); const side=String(body?.side||"").toUpperCase(); const orderType=String(body?.orderType||"").toUpperCase();
   const quote=Number(body?.quoteAmountUsdc||0); const base=body?.baseQuantity==null?null:Number(body.baseQuantity); const price=body?.limitPrice==null?null:Number(body.limitPrice); const conf=body?.confidence==null?null:Math.max(0,Math.min(99,Math.round(Number(body.confidence))));
   const rationale=String(body?.rationale||"").trim().slice(0,4000); const source=String(body?.source||"chatgpt-workspace").trim().slice(0,120)||"chatgpt-workspace";
   const expiresMs=Math.max(5*60*1000,Math.min(24*60*60*1000,Number(body?.expiresInMinutes||120)*60*1000));
   if(!/^[a-f0-9]{32,128}$/i.test(fp))return new Response(JSON.stringify({error:"invalid_account_fingerprint"}),{status:400,headers});
   if(!symbol.endsWith("USDC")||symbol==="USDCUSDC"||!["BUY","SELL"].includes(side)||!["LIMIT","MARKET"].includes(orderType))return new Response(JSON.stringify({error:"invalid_order"}),{status:400,headers});
   if(!(quote>0)||quote>10.0000001)return new Response(JSON.stringify({error:"invalid_quote_amount"}),{status:400,headers});
   if(orderType==="LIMIT"&&!(price&&price>0))return new Response(JSON.stringify({error:"limit_price_required"}),{status:400,headers});
   if(side==="SELL"&&!(base&&base>0))return new Response(JSON.stringify({error:"base_quantity_required_for_sell"}),{status:400,headers});
   const now=new Date().toISOString(); await sb.from("chk_trade_proposals").update({status:"expired",updated_at:now}).eq("account_fingerprint",fp).eq("status","pending").lt("expires_at",now);
   const {data:dupe,error:de}=await sb.from("chk_trade_proposals").select("id,status,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,expires_at").eq("account_fingerprint",fp).eq("status","pending").eq("symbol",symbol).eq("side",side).eq("order_type",orderType).order("created_at",{ascending:false}).limit(10); if(de)throw de;
   const same=(dupe||[]).find((x:any)=>Math.abs(Number(x.quote_amount_usdc)-quote)<1e-8&&Math.abs(Number(x.limit_price||0)-Number(price||0))<1e-10&&Math.abs(Number(x.base_quantity||0)-Number(base||0))<1e-10);
   if(same)return new Response(JSON.stringify({ok:true,duplicate:true,proposal:same}),{status:200,headers});
   const row:any={account_fingerprint:fp,exchange:"BYBIT",symbol,side,order_type:orderType,quote_amount_usdc:quote,base_quantity:base,limit_price:orderType==="LIMIT"?price:null,rationale,confidence:conf,source,status:"pending",expires_at:new Date(Date.now()+expiresMs).toISOString()};
   const {data,error}=await sb.from("chk_trade_proposals").insert(row).select(serverProjection).single(); if(error)throw error;
   return new Response(JSON.stringify({ok:true,duplicate:false,proposal:data}),{status:200,headers});
  }

  if(action==="server_list_proposals"){
   if(!serverOk)return new Response(JSON.stringify({error:"unauthorized"}),{status:401,headers}); const fp=String(body?.accountFingerprint||"").trim(); if(!/^[a-f0-9]{32,128}$/i.test(fp))return new Response(JSON.stringify({error:"invalid_account_fingerprint"}),{status:400,headers});
   const {data,error}=await sb.from("chk_trade_proposals").select(serverProjection).eq("account_fingerprint",fp).order("created_at",{ascending:false}).limit(Math.max(1,Math.min(100,Number(body?.limit||40)))); if(error)throw error; return new Response(JSON.stringify({ok:true,proposals:data||[]}),{status:200,headers});
  }

  if(action==="server_get_proposal"){
   if(!serverOk)return new Response(JSON.stringify({error:"unauthorized"}),{status:401,headers}); const id=String(body?.id||"").trim(); if(!/^[a-f0-9-]{36}$/i.test(id))return new Response(JSON.stringify({error:"invalid_proposal_id"}),{status:400,headers});
   const {data,error}=await sb.from("chk_trade_proposals").select(serverProjection).eq("id",id).maybeSingle(); if(error)throw error; if(!data)return new Response(JSON.stringify({error:"not_found"}),{status:404,headers}); return new Response(JSON.stringify({ok:true,proposal:data}),{status:200,headers});
  }

  if(action==="server_mark_result"){
   if(!serverOk)return new Response(JSON.stringify({error:"unauthorized"}),{status:401,headers}); const id=String(body?.id||"").trim(); const status=String(body?.status||"").toLowerCase(); const owner=String(body?.processingOwner||"").trim(); const fp=String(body?.accountFingerprint||"").trim();
   if(!/^[a-f0-9-]{36}$/i.test(id)||!["executed","error"].includes(status)||!/^[a-f0-9-]{32,80}$/i.test(owner)||!/^[a-f0-9]{32,128}$/i.test(fp))return new Response(JSON.stringify({error:"invalid_update"}),{status:400,headers});
   const result=body?.result&&typeof body.result==="object"&&!Array.isArray(body.result)?body.result:{}; const update:any={status,result,bybit_order_id:String(body?.bybitOrderId||"").trim().slice(0,120)||null,updated_at:new Date().toISOString()}; if(status==="executed")update.executed_at=new Date().toISOString();
   const {data,error}=await sb.from("chk_trade_proposals").update(update).eq("id",id).eq("account_fingerprint",fp).eq("status","processing").eq("processing_owner",owner).select("id,status,bybit_order_id,result,updated_at,executed_at").maybeSingle(); if(error)throw error; if(!data)return new Response(JSON.stringify({error:"proposal_state_conflict"}),{status:409,headers}); return new Response(JSON.stringify({ok:true,proposal:data}),{status:200,headers});
  }

  const deviceId=String(body?.deviceId||"").trim(); const deviceSecret=String(body?.deviceSecret||""); if(!/^[a-f0-9-]{32,80}$/i.test(deviceId)||deviceSecret.length<32||deviceSecret.length>256)return new Response(JSON.stringify({error:"invalid_device_credentials"}),{status:400,headers});
  const {data:device,error:devErr}=await sb.from("chk_crypto_snapshots").select("device_secret_hash,account_fingerprint").eq("device_id",deviceId).eq("exchange","BYBIT").maybeSingle(); if(devErr)throw devErr; if(!device||device.device_secret_hash!==await sha256(deviceSecret))return new Response(JSON.stringify({error:"device_auth_failed"}),{status:401,headers});
  const fp=String(device.account_fingerprint||""); if(!fp)return new Response(JSON.stringify({error:"bybit_not_synced"}),{status:409,headers}); const now=new Date().toISOString(); await sb.from("chk_trade_proposals").update({status:"expired",updated_at:now}).eq("account_fingerprint",fp).eq("status","pending").lt("expires_at",now);

  if(action==="list"){
   const {data:pending,error:e1}=await sb.from("chk_trade_proposals").select(appProjection).eq("account_fingerprint",fp).eq("status","pending").order("created_at",{ascending:false}).limit(30); if(e1)throw e1;
   const {data:recent,error:e2}=await sb.from("chk_trade_proposals").select(appProjection).eq("account_fingerprint",fp).neq("status","pending").order("updated_at",{ascending:false}).limit(30); if(e2)throw e2; return new Response(JSON.stringify({ok:true,pending:pending||[],recent:recent||[]}),{status:200,headers});
  }
  if(action==="claim"){
   const id=String(body?.id||"").trim(); if(!/^[a-f0-9-]{36}$/i.test(id))return new Response(JSON.stringify({error:"invalid_proposal_id"}),{status:400,headers}); const at=new Date().toISOString();
   const {data,error}=await sb.from("chk_trade_proposals").update({status:"processing",processing_started_at:at,processing_owner:deviceId,updated_at:at}).eq("id",id).eq("account_fingerprint",fp).eq("status","pending").gt("expires_at",at).select(appProjection).maybeSingle(); if(error)throw error; if(!data)return new Response(JSON.stringify({error:"proposal_not_claimable"}),{status:409,headers}); return new Response(JSON.stringify({ok:true,proposal:data}),{status:200,headers});
  }
  if(action==="mark_result"){
   const id=String(body?.id||"").trim(); const status=String(body?.status||"").toLowerCase(); if(!/^[a-f0-9-]{36}$/i.test(id)||!["executed","error","rejected"].includes(status))return new Response(JSON.stringify({error:"invalid_update"}),{status:400,headers}); const expectedStatus=status==="rejected"?"pending":"processing"; const result=body?.result&&typeof body.result==="object"&&!Array.isArray(body.result)?body.result:{}; const update:any={status,result,bybit_order_id:String(body?.bybitOrderId||"").trim().slice(0,120)||null,updated_at:new Date().toISOString()}; if(status==="executed")update.executed_at=new Date().toISOString();
   let q=sb.from("chk_trade_proposals").update(update).eq("id",id).eq("account_fingerprint",fp).eq("status",expectedStatus); if(expectedStatus==="processing")q=q.eq("processing_owner",deviceId); const {data,error}=await q.select("id,status,bybit_order_id,updated_at,executed_at").maybeSingle(); if(error)throw error; if(!data)return new Response(JSON.stringify({error:"proposal_state_conflict"}),{status:409,headers}); return new Response(JSON.stringify({ok:true,proposal:data}),{status:200,headers});
  }
  if(action==="delete_history"){
   const id=String(body?.id||"").trim(); if(!/^[a-f0-9-]{36}$/i.test(id))return new Response(JSON.stringify({error:"invalid_proposal_id"}),{status:400,headers}); const {data:ex,error:re}=await sb.from("chk_trade_proposals").select("id,status,result").eq("id",id).eq("account_fingerprint",fp).maybeSingle(); if(re)throw re; if(!ex)return new Response(JSON.stringify({error:"not_found"}),{status:404,headers}); if(["pending","processing"].includes(ex.status))return new Response(JSON.stringify({error:"active_proposal_cannot_be_deleted"}),{status:409,headers}); const os=String(ex?.result?.orderStatus||"").toLowerCase(); if(["new","partiallyfilled","untriggered"].includes(os))return new Response(JSON.stringify({error:"open_bybit_order_cannot_be_deleted"}),{status:409,headers}); const {error}=await sb.from("chk_trade_proposals").delete().eq("id",id).eq("account_fingerprint",fp); if(error)throw error; return new Response(JSON.stringify({ok:true,deleted:true,id}),{status:200,headers});
  }
  return new Response(JSON.stringify({error:"unknown_action"}),{status:400,headers});
 }catch(e){console.error("chk-trade-proposals",e);return new Response(JSON.stringify({error:"trade_proposals_failed",message:String(e?.message||e).slice(0,180)}),{status:500,headers});}
});