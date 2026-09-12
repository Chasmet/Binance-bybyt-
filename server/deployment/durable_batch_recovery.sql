ALTER TABLE public.chk_trade_proposals
  ADD COLUMN batch_id uuid,
  ADD COLUMN batch_index integer CHECK (batch_index >= 0),
  ADD COLUMN submission_tracking boolean NOT NULL DEFAULT false,
  ADD COLUMN submission_attempts integer NOT NULL DEFAULT 0 CHECK (submission_attempts BETWEEN 0 AND 2),
  ADD COLUMN last_submission_at timestamptz;

CREATE UNIQUE INDEX chk_trade_batch_position ON public.chk_trade_proposals(account_fingerprint,batch_id,batch_index)
  WHERE batch_id IS NOT NULL;

-- Called only after device authentication in the Edge Function, immediately before POST Bybit.
-- A lost response consumes an attempt conservatively. Never reset counters on a timeout.
CREATE OR REPLACE FUNCTION public.chk_reserve_submission(p_id uuid, p_account text, p_owner text)
RETURNS SETOF public.chk_trade_proposals
LANGUAGE sql SECURITY INVOKER SET search_path = public AS $$
  UPDATE public.chk_trade_proposals p
  SET submission_attempts = p.submission_attempts + 1, last_submission_at = now(), updated_at = now()
  WHERE p.id = p_id AND p.account_fingerprint = p_account AND p.processing_owner = p_owner
    AND p.status = 'processing' AND p.submission_tracking AND p.expires_at > now()
    AND p.submission_attempts < 2
    AND (p.last_submission_at IS NULL OR p.last_submission_at < now() - interval '60 seconds')
    AND NOT EXISTS (
      SELECT 1 FROM public.chk_trade_proposals prior
      WHERE prior.account_fingerprint = p.account_fingerprint AND prior.batch_id = p.batch_id
        AND prior.batch_index < p.batch_index
        AND (prior.status IN ('pending','processing') OR
          (prior.status = 'executed' AND (prior.bybit_order_id IS NULL OR
            coalesce(prior.result->>'orderStatus','') NOT IN ('New','PartiallyFilled','Filled','Cancelled','PartiallyFilledCanceled'))))
    )
  RETURNING p.*;
$$;
REVOKE ALL ON FUNCTION public.chk_reserve_submission(uuid,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.chk_reserve_submission(uuid,text,text) TO service_role;
