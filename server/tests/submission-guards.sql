-- Integration verification: synthetic account, no device, no Bybit calls.
-- Everything is rolled back, including the test proposals.
BEGIN;
DO $$
DECLARE
  account_id text := replace(gen_random_uuid()::text,'-','') || replace(gen_random_uuid()::text,'-','');
  owner_id text := gen_random_uuid()::text;
  batch uuid := gen_random_uuid();
  first_id uuid := gen_random_uuid();
  second_id uuid := gen_random_uuid();
  n int;
BEGIN
  IF has_function_privilege('anon','public.chk_reserve_submission(uuid,text,text)','EXECUTE')
    OR has_function_privilege('authenticated','public.chk_reserve_submission(uuid,text,text)','EXECUTE')
    OR NOT has_function_privilege('service_role','public.chk_reserve_submission(uuid,text,text)','EXECUTE')
  THEN RAISE EXCEPTION 'RPC permissions incorrect'; END IF;

  INSERT INTO public.chk_trade_proposals(id,account_fingerprint,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,status,expires_at,processing_owner,batch_id,batch_index,submission_tracking)
  VALUES (first_id,account_id,'RENDERUSDC','SELL','LIMIT',7.35,5,1.47,'processing',now()+interval '1 hour',owner_id,batch,0,true),
    (second_id,account_id,'RENDERUSDC','SELL','LIMIT',7.35,5,1.47,'processing',now()+interval '1 hour',owner_id,batch,1,true);
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,'wrong-account',owner_id);
  IF n<>0 THEN RAISE EXCEPTION 'Account guard failed'; END IF;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,account_id,'wrong-owner');
  IF n<>0 THEN RAISE EXCEPTION 'Owner guard failed'; END IF;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(second_id,account_id,owner_id);
  IF n<>0 THEN RAISE EXCEPTION 'Batch sequencing guard failed'; END IF;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,account_id,owner_id);
  IF n<>1 THEN RAISE EXCEPTION 'Initial reservation failed'; END IF;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,account_id,owner_id);
  IF n<>0 THEN RAISE EXCEPTION 'Cooldown guard failed'; END IF;
  UPDATE public.chk_trade_proposals SET last_submission_at=now()-interval '61 seconds' WHERE id=first_id;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,account_id,owner_id);
  IF n<>1 THEN RAISE EXCEPTION 'Controlled retry failed'; END IF;
  UPDATE public.chk_trade_proposals SET last_submission_at=now()-interval '61 seconds' WHERE id=first_id;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(first_id,account_id,owner_id);
  IF n<>0 THEN RAISE EXCEPTION 'Attempt cap failed'; END IF;
  UPDATE public.chk_trade_proposals SET status='executed',bybit_order_id='simulation-only',result='{"orderStatus":"New"}'::jsonb WHERE id=first_id;
  SELECT count(*) INTO n FROM public.chk_reserve_submission(second_id,account_id,owner_id);
  IF n<>1 THEN RAISE EXCEPTION 'Confirmed predecessor did not unblock next'; END IF;

  -- Non-batch proposals retain the old duplicate guard.
  INSERT INTO public.chk_trade_proposals(account_fingerprint,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,expires_at)
  VALUES (account_id,'RENDERUSDC','SELL','LIMIT',7.35,5,1.47,now()+interval '1 hour');
  BEGIN
    INSERT INTO public.chk_trade_proposals(account_fingerprint,symbol,side,order_type,quote_amount_usdc,base_quantity,limit_price,expires_at)
    VALUES (account_id,'RENDERUSDC','SELL','LIMIT',7.35,5,1.47,now()+interval '1 hour');
    RAISE EXCEPTION 'Legacy duplicate guard failed';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
END $$;
SELECT 'PASS: same-pair batch, account/owner isolation, RPC privileges, confirmed predecessor, cooldown, attempt cap, legacy duplicate guard' AS verification;
ROLLBACK;
