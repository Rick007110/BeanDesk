-- BeanDesk on its own backend (backend/): accounts are made and checked by the backend, which
-- uses the service role for that and nothing else.

-- A new anonymous account, at someone's first report. Returns its id, which goes in the token.
create function public.create_anonymous_account()
returns uuid
language sql
volatile
security definer
set search_path = ''
as $$
    insert into auth.users (is_anonymous) values (true) returning id
$$;

-- Whether a token's account still exists: it goes with "account verwijderen", and an empty
-- anonymous account after 30 days (delete_idle_anonymous_accounts).
create function public.account_exists(p_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (select 1 from auth.users where id = p_id)
$$;

revoke execute on function public.create_anonymous_account() from public, anon, authenticated;
revoke execute on function public.account_exists(uuid) from public, anon, authenticated;
grant execute on function public.create_anonymous_account() to service_role;
grant execute on function public.account_exists(uuid) to service_role;
