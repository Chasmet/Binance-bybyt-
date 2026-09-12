export const MAX_ORDER_USDC = 30;
export const orderLinkId = (id: string) => `chk-${id.replaceAll('-', '').slice(0,28)}`;
const REAL_STATES: Record<string,string> = {New:'OPEN',PartiallyFilled:'PARTIALLY_FILLED',Filled:'FILLED',Rejected:'REJECTED',Cancelled:'CANCELLED',PartiallyFilledCanceled:'CANCELLED'};

export function validateOrder(order: any) {
  const symbol = String(order.symbol || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const side = String(order.side || "").toUpperCase();
  const quote = Number(order.quoteAmountUsdc);
  const price = Number(order.limitPrice);
  const base = order.baseQuantity == null ? null : Number(order.baseQuantity);
  if (!/^[A-Z0-9]{2,20}USDC$/.test(symbol) || symbol === "USDCUSDC" || !["BUY", "SELL"].includes(side) ||
      (order.orderType && order.orderType !== "LIMIT") || !Number.isFinite(quote) || quote <= 1 || quote > MAX_ORDER_USDC ||
      !Number.isFinite(price) || price <= 0 ||
      (base !== null && (!Number.isFinite(base) || base <= 0 || base * price > quote + 1e-8)) ||
      (side === "SELL" && base === null)) throw new Error("invalid_batch_order");
  return {symbol, side, order_type: "LIMIT", quote_amount_usdc: quote, limit_price: price, base_quantity: base};
}

export function summarize(ids: string[], rows: any[], now = Date.now()) {
  const orders = ids.map(id => {
    const row = rows.find(x => x.id === id);
    if (!row) return {id, state: "missing", status:'UNKNOWN', confirmed: false, resolved:false, orderLinkId:orderLinkId(id)};
    const exchangeStatus = String(row.result?.orderStatus || "");
    const confirmed = row.status === "executed" && !!row.bybit_order_id &&
      ["New", "PartiallyFilled", "Filled"].includes(exchangeStatus);
    const state = confirmed ? "placed" : row.status === "pending" && Date.parse(row.expires_at) <= now ? "expired" :
      row.status === "pending" && row.result?.blocked ? "blocked" :
      row.status === "executed" ? "unconfirmed_or_closed" : row.status;
    const status = row.bybit_order_id && REAL_STATES[exchangeStatus] ? REAL_STATES[exchangeStatus] :
      row.status === 'error' ? 'FAILED' : row.status === 'rejected' ? 'REJECTED' :
      state === 'expired' ? 'EXPIRED' : state === 'blocked' ? 'BLOCKED' : row.status === 'processing' ? 'PROCESSING' : 'PENDING';
    return {id, state, status, confirmed, resolved:!['UNKNOWN','PENDING','PROCESSING'].includes(status),
      symbol:row.symbol,side:row.side,orderLinkId:orderLinkId(id),batchIndex:row.batch_index,
      orderId: row.bybit_order_id || null, exchangeStatus, proposalStatus:row.status,
      submissionAttempts:row.submission_attempts||0,lastSubmissionAt:row.last_submission_at,
      reason: row.result?.reason || row.result?.error || null};
  });
  const confirmed = orders.filter(x => x.confirmed).length;
  return {allConfirmed: ids.length > 0 && confirmed === ids.length, reportReady:ids.length>0 && orders.every(x=>x.resolved), confirmed, total: ids.length,
    pending: orders.filter(x => !x.resolved).length, orders};
}

/** Read-only wait. A timeout is pending, never success. All lookups are account-scoped. */
export async function waitForBatch(sb: any, account: string, ids: string[], timeoutMs = 20000) {
  if (!Array.isArray(ids) || ids.length < 1 || ids.length > 20 || new Set(ids).size !== ids.length ||
      ids.some(id => !/^[a-f0-9-]{36}$/i.test(id))) throw new Error("invalid_proposal_ids");
  const deadline = Date.now() + Math.max(0, Math.min(20000, Number(timeoutMs) || 0));
  while (true) {
    const {data, error} = await sb.from("chk_trade_proposals")
      .select("id,status,bybit_order_id,result,expires_at,symbol,side,batch_index,submission_attempts,last_submission_at")
      .eq("account_fingerprint", account).in("id", ids);
    if (error) throw error;
    const result = summarize(ids, data || []);
    if (result.allConfirmed || result.pending === 0 || Date.now() >= deadline) return result;
    await new Promise(resolve => setTimeout(resolve, Math.min(1000, deadline - Date.now())));
  }
}

export async function createBatch(sb: any, account: string, body: any) {
  const batchId = String(body.batchId || "");
  if (!/^[a-f0-9-]{36}$/i.test(batchId) || !Array.isArray(body.orders) || body.orders.length < 1 || body.orders.length > 20)
    throw new Error("invalid_batch");
  // Validate every row before insertion. One multi-row insert is atomic.
  const validated = body.orders.map(validateOrder);
  const ids: string[] = [];
  for (let i = 0; i < validated.length; i++) {
    const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${account}:${batchId}:${i}`)));
    const hex = Array.from(bytes.slice(0, 16), b => b.toString(16).padStart(2, "0")).join("");
    ids.push(`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`);
  }
  const rows = validated.map((order: any, i: number) => ({...order, id: ids[i], account_fingerprint: account,batch_id:batchId,batch_index:i,
    exchange: "BYBIT", source: `chatgpt-batch:${batchId}`, status: "pending",
    rationale: String(body.orders[i].rationale || "").slice(0,4000), expires_at: new Date(Date.now() + 120 * 60000).toISOString()}));
  const {error} = await sb.from("chk_trade_proposals").insert(rows);
  if (error) {
    if (error.code !== "23505") throw error;
    const {data, error: readError} = await sb.from("chk_trade_proposals").select("*").eq("account_fingerprint", account).eq("source", `chatgpt-batch:${batchId}`);
    if (readError) throw readError;
    if (!data || data.length !== rows.length || rows.some((row: any) => {
      const old = data.find((x: any) => x.id === row.id);
      return !old || old.symbol !== row.symbol || old.side !== row.side || old.order_type !== row.order_type ||
        Number(old.quote_amount_usdc) !== row.quote_amount_usdc || Number(old.limit_price) !== row.limit_price ||
        (old.base_quantity == null ? null : Number(old.base_quantity)) !== row.base_quantity;
    })) throw new Error("batch_id_conflict");
  }
  // Persist and acknowledge immediately. Waiting belongs to a separate resumable read request.
  return {ok: true, batchId, proposalIds: ids, ...(await waitForBatch(sb, account, ids, 0))};
}

/** Only the authenticated internal server supplies this read-only Bybit observation. */
export async function reconcileResult(sb:any,account:string,body:any) {
  const id=String(body.id||'');
  const {data:row,error}=await sb.from('chk_trade_proposals').select('*').eq('id',id).eq('account_fingerprint',account).maybeSingle();
  if(error)throw error;
  if(!row)throw new Error('proposal_not_found');
  const observed=body.observed;
  if(!observed?.orderId||observed.orderLinkId!==orderLinkId(id)||observed.symbol!==row.symbol||
      String(observed.side).toUpperCase()!==row.side||!REAL_STATES[observed.orderStatus])throw new Error('invalid_bybit_observation');
  if(!['processing','executed'].includes(row.status))return {ok:true,changed:false};
  const status=observed.orderStatus==='Rejected'?'error':'executed';
  const result={...row.result,orderId:observed.orderId,orderLinkId:observed.orderLinkId,orderStatus:observed.orderStatus,
    symbol:row.symbol,side:row.side,requestedQty:observed.qty,requestedPrice:observed.price,
    executedQty:Number(observed.cumExecQty||0),executedValueUsdc:Number(observed.cumExecValue||0),
    verifiedAt:new Date().toISOString(),verificationSource:'bybit_rest_order_link_id'};
  const {error:writeError}=await sb.from('chk_trade_proposals').update({status,bybit_order_id:String(observed.orderId),result,
    executed_at:row.executed_at||new Date().toISOString(),updated_at:new Date().toISOString()})
    .eq('id',id).eq('account_fingerprint',account).eq('status',row.status).eq('updated_at',row.updated_at);
  if(writeError)throw writeError;
  return {ok:true};
}
