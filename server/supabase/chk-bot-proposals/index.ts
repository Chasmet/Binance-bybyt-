import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const headers = {
  "content-type": "application/json",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff"
};

async function sha256(value: string) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function cleanSymbol(value: unknown) {
  const raw = String(value || "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 30);
  if (raw.endsWith("USDT")) return raw.slice(0, -4) + "USDC";
  if (raw.endsWith("USDC")) return raw;
  return raw + "USDC";
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response(JSON.stringify({ error: "method_not_allowed" }), { status: 405, headers });
  try {
    const body = await req.json();
    const action = String(body?.action || "create").trim().toLowerCase();
    if (action !== "create") return new Response(JSON.stringify({ error: "unknown_action" }), { status: 400, headers });

    const deviceId = String(body?.deviceId || "").trim();
    const deviceSecret = String(body?.deviceSecret || "");
    if (!/^[a-f0-9-]{32,80}$/i.test(deviceId) || deviceSecret.length < 32 || deviceSecret.length > 256) {
      return new Response(JSON.stringify({ error: "invalid_device_credentials" }), { status: 400, headers });
    }

    const sb = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
      { auth: { persistSession: false } }
    );

    const { data: device, error: deviceError } = await sb
      .from("chk_crypto_snapshots")
      .select("device_secret_hash,account_fingerprint")
      .eq("device_id", deviceId)
      .eq("exchange", "BYBIT")
      .maybeSingle();
    if (deviceError) throw deviceError;
    if (!device || device.device_secret_hash !== await sha256(deviceSecret)) {
      return new Response(JSON.stringify({ error: "device_auth_failed" }), { status: 401, headers });
    }

    const accountFingerprint = String(device.account_fingerprint || "").trim();
    if (!/^[a-f0-9]{32,128}$/i.test(accountFingerprint)) {
      return new Response(JSON.stringify({ error: "bybit_not_synced" }), { status: 409, headers });
    }

    const symbol = cleanSymbol(body?.symbol);
    const side = String(body?.side || "").trim().toUpperCase();
    const quote = Number(body?.quoteAmountUsdc || 0);
    const limitPrice = Number(body?.limitPrice || 0);
    const baseQuantity = body?.baseQuantity == null ? null : Number(body.baseQuantity);
    const rationale = String(body?.rationale || "Bot CHK").trim().slice(0, 4000);
    const expiresInMinutes = Math.max(5, Math.min(24 * 60, Number(body?.expiresInMinutes || 120)));

    if (!/^[A-Z0-9]{2,24}USDC$/.test(symbol) || symbol === "USDCUSDC") {
      return new Response(JSON.stringify({ error: "invalid_symbol" }), { status: 400, headers });
    }
    if (!["BUY", "SELL"].includes(side)) {
      return new Response(JSON.stringify({ error: "invalid_side" }), { status: 400, headers });
    }
    if (!(quote >= 1 && quote <= 30.0000001) || !(limitPrice > 0)) {
      return new Response(JSON.stringify({ error: "invalid_amount_or_price" }), { status: 400, headers });
    }
    if (side === "SELL" && !(baseQuantity && baseQuantity > 0)) {
      return new Response(JSON.stringify({ error: "base_quantity_required_for_sell" }), { status: 400, headers });
    }

    const now = new Date().toISOString();
    await sb.from("chk_trade_proposals")
      .update({ status: "expired", updated_at: now })
      .eq("account_fingerprint", accountFingerprint)
      .eq("status", "pending")
      .lt("expires_at", now);

    const { data: existing, error: existingError } = await sb
      .from("chk_trade_proposals")
      .select("id,exchange,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,rationale,confidence,source,status,expires_at,created_at")
      .eq("account_fingerprint", accountFingerprint)
      .eq("status", "pending")
      .eq("symbol", symbol)
      .eq("side", side)
      .eq("order_type", "LIMIT")
      .order("created_at", { ascending: false })
      .limit(20);
    if (existingError) throw existingError;

    const duplicate = (existing || []).find((x: any) =>
      Math.abs(Number(x.quote_amount_usdc) - quote) < 1e-8 &&
      Math.abs(Number(x.limit_price || 0) - limitPrice) < 1e-10 &&
      Math.abs(Number(x.base_quantity || 0) - Number(baseQuantity || 0)) < 1e-10
    );
    if (duplicate) {
      return new Response(JSON.stringify({ ok: true, duplicate: true, proposal: duplicate }), { status: 200, headers });
    }

    const expiresAt = new Date(Date.now() + expiresInMinutes * 60_000).toISOString();
    const row = {
      account_fingerprint: accountFingerprint,
      exchange: "BYBIT",
      symbol,
      side,
      order_type: "LIMIT",
      quote_amount_usdc: quote,
      base_quantity: side === "SELL" ? baseQuantity : null,
      limit_price: limitPrice,
      rationale,
      confidence: null,
      source: "bot-chk-v1",
      status: "pending",
      expires_at: expiresAt
    };

    const { data, error } = await sb
      .from("chk_trade_proposals")
      .insert(row)
      .select("id,exchange,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,rationale,confidence,source,status,expires_at,created_at")
      .single();
    if (error) throw error;

    return new Response(JSON.stringify({ ok: true, duplicate: false, proposal: data }), { status: 200, headers });
  } catch (error) {
    console.error("chk-bot-proposals", error);
    return new Response(JSON.stringify({ error: "bot_proposal_failed", message: String((error as any)?.message || error).slice(0, 180) }), { status: 500, headers });
  }
});

