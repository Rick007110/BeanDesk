-- Minimal stand-ins for what Supabase provides (auth, roles), so the migrations can run
-- against a plain PostGIS database in tests.

do $$
begin
    create role anon nologin;
exception when duplicate_object then null;
end $$;

do $$
begin
    create role authenticated nologin;
exception when duplicate_object then null;
end $$;

do $$
begin
    create role service_role nologin bypassrls;
exception when duplicate_object then null;
end $$;

create schema auth;

create table auth.users (
    id uuid primary key default gen_random_uuid(),
    is_anonymous boolean not null default false
);

create function auth.uid() returns uuid
language sql stable
as $$
    select coalesce(
        nullif(current_setting('request.jwt.claim.sub', true), ''),
        (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
    )::uuid
$$;

grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;

grant usage on schema public to anon, authenticated, service_role;
-- Supabase gives the service role (edge functions, the dashboard) every table in public.
alter default privileges in schema public grant all on tables to service_role;
alter default privileges in schema public grant all on sequences to service_role;
