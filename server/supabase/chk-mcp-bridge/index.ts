import { createBatch, waitForBatch, reconcileResult } from "./batch.ts";
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const EXPECTED_HASH="9413103e05bba439c40fb544ac53909b3d5cd96b1dbbb2c2ade2f44504e05b7c";
const H={"content-type":"application/json","cache-control":"no-store","x-content-type-options":"nosniff"};
async function sha256(v:string){const d=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(v));return Array.from(new Uint8Array(d)).map(b=>b.toString(16).padStart(2,"0")).join("");}
function out(status:number,data:any){return new Response(JSON.stringify(data),{status,headers:H});}
function fp(v:any){const s=String(v||"").trim();if(!/^[a-f0-9]{32,128}$/i.test(s))throw new Error("invalid_account_fingerprint");return s;}
function sym(v:any){const s=String(v||"").trim().toUpperCase().replace(/[^A-Z0-9]/g,"").slice(0,30);if(!s.endsWith("USDC")||s==="USDCUSDC")throw new Error("invalid_symbol");return s;}
function uuid(v:any){const s=String(v||"").trim();if(!/^[0-9a-f-]{36}$/i.test(s))throw new Error("invalid_id");return s;}
async function deviceForAccount(sb:any,account:string){
  const {data,error}=await sb.from("chk_crypto_snapshots").select("device_id").eq("account_fingerprint",account).eq("exchange","BYBIT").order("updated_at",{ascending:false}).limit(1).maybeSingle();
  if(error)throw error;
  if(!data?.device_id)throw new Error("bybit_device_not_found");
  return String(data.device_id);
}

Deno.serve(async(req:Request)=>{
 if(req.method!=="POST")return out(405,{error:"method_not_allowed"});
 try{
  const supplied=String(req.headers.get("x-chk-internal-token")||"");
  if(!supplied||await sha256(supplied)!==EXPECTED_HASH)return out(401,{error:"unauthorized"});
  const b=await req.json();
  const action=String(b?.action||"").trim();
  const sb=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{persistSession:false}});
  const account=fp(b?.accountFingerprint);

  if(action==="create_trade_batch") return out(200,await createBatch(sb,account,b));
  if(action==="reconcile_trade_result") return out(200,await reconcileResult(sb,account,b));
  if(action==="wait_trade_batch") return out(200,{ok:true,...await waitForBatch(sb,account,b.proposalIds,b.timeoutMs)});
  if(action==="list_notes"){
    const {data,error}=await sb.from("chk_crypto_notes").select("id,exchange,kind,content,source,status,created_at,updated_at").eq("account_fingerprint",account).eq("status","active").order("created_at",{ascending:false}).limit(Math.max(1,Math.min(200,Number(b?.limit||100))));if(error)throw error;return out(200,{ok:true,notes:data||[]});
  }
  if(action==="create_note"){
    const content=String(b?.content||"").trim().slice(0,12000);if(!content)return out(400,{error:"empty_note"});
    const row={account_fingerprint:account,exchange:String(b?.exchange||"GLOBAL").toUpperCase().slice(0,16),kind:String(b?.kind||"ANALYSIS").toUpperCase().slice(0,24),content,source:"chatgpt-workspace-v15"};
    const {data,error}=await sb.from("chk_crypto_notes").insert(row).select("id,exchange,kind,content,source,status,created_at,updated_at").single();if(error)throw error;return out(200,{ok:true,note:data});
  }
  if(action==="list_trade_proposals"){
    const {data,error}=await sb.from("chk_trade_proposals").select("id,exchange,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,rationale,confidence,source,status,expires_at,bybit_order_id,result,created_at,updated_at,executed_at").eq("account_fingerprint",account).order("created_at",{ascending:false}).limit(Math.max(1,Math.min(100,Number(b?.limit||40))));if(error)throw error;return out(200,{ok:true,proposals:data||[]});
  }
  if(action==="create_trade_proposal"){
    const symbol=sym(b?.symbol),side=String(b?.side||"").toUpperCase(),orderType=String(b?.orderType||"").toUpperCase(),quote=Number(b?.quoteAmountUsdc||0),base=b?.baseQuantity==null?null:Number(b.baseQuantity),price=b?.limitPrice==null?null:Number(b.limitPrice);
    if(!["BUY","SELL"].includes(side)||!["LIMIT","MARKET"].includes(orderType)||!(quote>0)||quote>30.0000001)return out(400,{error:"invalid_order"});
    if(orderType==="LIMIT"&&!(price&&price>0))return out(400,{error:"limit_price_required"});
    if(side==="SELL"&&!(base&&base>0))return out(400,{error:"base_quantity_required_for_sell"});
    const now=new Date().toISOString();await sb.from("chk_trade_proposals").update({status:"expired",updated_at:now}).eq("account_fingerprint",account).eq("status","pending").lt("expires_at",now);
    const expires=Math.max(5,Math.min(1440,Number(b?.expiresInMinutes||120)));
    const {data:dupes,error:de}=await sb.from("chk_trade_proposals").select("id,status,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,expires_at").eq("account_fingerprint",account).eq("status","pending").eq("symbol",symbol).eq("side",side).eq("order_type",orderType).order("created_at",{ascending:false}).limit(10);if(de)throw de;
    const same=(dupes||[]).find((x:any)=>Math.abs(Number(x.quote_amount_usdc)-quote)<1e-8&&Math.abs(Number(x.limit_price||0)-Number(price||0))<1e-10&&Math.abs(Number(x.base_quantity||0)-Number(base||0))<1e-10);if(same)return out(200,{ok:true,duplicate:true,proposal:same});
    const row:any={account_fingerprint:account,exchange:"BYBIT",symbol,side,order_type:orderType,quote_amount_usdc:quote,base_quantity:base,limit_price:orderType==="LIMIT"?price:null,rationale:String(b?.rationale||"").trim().slice(0,4000),confidence:b?.confidence==null?null:Math.max(0,Math.min(99,Math.round(Number(b.confidence)))),source:"chatgpt-workspace-v15",status:"pending",expires_at:new Date(Date.now()+expires*60000).toISOString()};
    const {data,error}=await sb.from("chk_trade_proposals").insert(row).select("id,exchange,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,rationale,confidence,source,status,expires_at,created_at,updated_at").single();if(error)throw error;return out(200,{ok:true,duplicate:false,proposal:data});
  }
  if(action==="list_cancel_proposals"){
    const {data,error}=await sb.from("chk_cancel_proposals").select("id,exchange,symbol,target_order_id,target_order_link_id,rationale,confidence,status,expires_at,result,created_at,updated_at,executed_at,replacement_side,replacement_order_type,replacement_quote_amount_usdc,replacement_base_quantity,replacement_limit_price,replacement_rationale,replacement_confidence").eq("account_fingerprint",account).order("created_at",{ascending:false}).limit(Math.max(1,Math.min(100,Number(b?.limit||40))));if(error)throw error;return out(200,{ok:true,proposals:data||[]});
  }
  if(action==="create_cancel_proposal"){
    const symbol=sym(b?.symbol),target=String(b?.targetOrderId||"").trim().slice(0,128);if(!target)return out(400,{error:"target_order_required"});
    const replacementQuote=b?.replacementQuoteAmountUsdc==null?null:Number(b.replacementQuoteAmountUsdc);if(replacementQuote!=null&&(!Number.isFinite(replacementQuote)||replacementQuote<=1||replacementQuote>30))return out(400,{error:"invalid_replacement_amount"});
    const {data:existing,error:ee}=await sb.from("chk_cancel_proposals").select("id,status,symbol,target_order_id,expires_at").eq("account_fingerprint",account).in("status",["pending","processing","executed"]).eq("target_order_id",target).order("created_at",{ascending:false}).limit(1).maybeSingle();if(ee)throw ee;if(existing)return out(200,{ok:true,duplicate:true,proposal:existing});
    const expires=Math.max(5,Math.min(1440,Number(b?.expiresInMinutes||120)));
    const row:any={account_fingerprint:account,exchange:"BYBIT",symbol,target_order_id:target,target_order_link_id:String(b?.targetOrderLinkId||"").trim().slice(0,128)||null,rationale:String(b?.rationale||"").trim().slice(0,4000),confidence:b?.confidence==null?null:Math.max(0,Math.min(99,Math.round(Number(b.confidence)))),status:"pending",expires_at:new Date(Date.now()+expires*60000).toISOString(),replacement_side:b?.replacementSide?String(b.replacementSide).toUpperCase():null,replacement_order_type:b?.replacementOrderType?String(b.replacementOrderType).toUpperCase():null,replacement_quote_amount_usdc:replacementQuote,replacement_base_quantity:b?.replacementBaseQuantity==null?null:Number(b.replacementBaseQuantity),replacement_limit_price:b?.replacementLimitPrice==null?null:Number(b.replacementLimitPrice),replacement_rationale:b?.replacementRationale?String(b.replacementRationale).slice(0,4000):null,replacement_confidence:b?.replacementConfidence==null?null:Math.max(0,Math.min(99,Math.round(Number(b.replacementConfidence))))};
    const {data,error}=await sb.from("chk_cancel_proposals").insert(row).select().single();if(error)throw error;return out(200,{ok:true,duplicate:false,proposal:data});
  }

  if(action==="list_alerts"){
    const deviceId=await deviceForAccount(sb,account);
    const {data,error}=await sb.from("chk_binance_alerts").select("id,symbol,pair,condition,target_price,label,rationale,source,enabled,one_shot,triggered_at,last_price,created_at,updated_at").eq("device_id",deviceId).order("enabled",{ascending:false}).order("created_at",{ascending:false}).limit(Math.max(1,Math.min(200,Number(b?.limit||100))));if(error)throw error;return out(200,{ok:true,alerts:data||[]});
  }
  if(action==="create_alert"){
    const deviceId=await deviceForAccount(sb,account);const pair=sym(b?.symbol);const base=pair.slice(0,-4);const condition=String(b?.condition||"").toLowerCase();const target=Number(b?.targetPrice);
    if(!["above","below"].includes(condition)||!Number.isFinite(target)||target<=0)return out(400,{error:"invalid_alert"});
    const {data:existing,error:ee}=await sb.from("chk_binance_alerts").select("id,symbol,pair,condition,target_price,label,rationale,source,enabled,one_shot,created_at,updated_at").eq("device_id",deviceId).eq("pair",pair).eq("condition",condition).eq("target_price",target).eq("enabled",true).limit(1).maybeSingle();if(ee)throw ee;if(existing)return out(200,{ok:true,duplicate:true,alert:existing});
    const row={device_id:deviceId,symbol:base,pair,condition,target_price:target,label:String(b?.label||`${pair} ${condition==="above"?"≥":"≤"} ${target}`).slice(0,120),rationale:String(b?.rationale||"").slice(0,700),source:"chatgpt-workspace-v15",enabled:b?.enabled!==false,one_shot:b?.oneShot!==false,updated_at:new Date().toISOString()};
    const {data,error}=await sb.from("chk_binance_alerts").insert(row).select().single();if(error)throw error;return out(200,{ok:true,duplicate:false,alert:data});
  }
  if(action==="update_alert"){
    const deviceId=await deviceForAccount(sb,account);const id=uuid(b?.id);const patch:any={updated_at:new Date().toISOString()};
    if(b?.targetPrice!==undefined){const v=Number(b.targetPrice);if(!Number.isFinite(v)||v<=0)return out(400,{error:"invalid_target"});patch.target_price=v;}
    if(b?.condition!==undefined){const c=String(b.condition).toLowerCase();if(!["above","below"].includes(c))return out(400,{error:"invalid_condition"});patch.condition=c;}
    if(b?.label!==undefined)patch.label=String(b.label).slice(0,120);if(b?.rationale!==undefined)patch.rationale=String(b.rationale).slice(0,700);if(b?.enabled!==undefined)patch.enabled=b.enabled===true;if(b?.oneShot!==undefined)patch.one_shot=b.oneShot===true;
    const {data,error}=await sb.from("chk_binance_alerts").update(patch).eq("id",id).eq("device_id",deviceId).select().maybeSingle();if(error)throw error;return out(data?200:404,data?{ok:true,alert:data}:{error:"not_found"});
  }
  if(action==="delete_alert"){
    const deviceId=await deviceForAccount(sb,account);const id=uuid(b?.id);const {data,error}=await sb.from("chk_binance_alerts").delete().eq("id",id).eq("device_id",deviceId).select("id").maybeSingle();if(error)throw error;return out(data?200:404,data?{ok:true,id:data.id}:{error:"not_found"});
  }

  return out(400,{error:"unknown_action"});
 }catch(e){console.error("chk-mcp-bridge",e);const message=String(e?.message||e).slice(0,180);const invalid=/^(invalid_|batch_id_conflict|proposal_not_found)/.test(message);return out(invalid?400:503,{error:invalid?message:"bridge_unavailable",message,retryable:!invalid});}
});
