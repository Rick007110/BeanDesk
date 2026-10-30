-- What the migrations expect around them: the roles anon, authenticated and service_role, and
-- accounts in auth.users with auth.uid() for the row level security rules. The backend (backend/)
-- switches to these roles per request and sets the user's id in request.jwt.claims, the way
-- Supabase did. Runs once, before the migrations (deploy/migrate.sh, and the tests).

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
    is_anonymous boolean not null default false,
    created_at timestamptz default now()
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
-- The service role (the backend's search and nightly tasks) gets every table in public.
alter default privileges in schema public grant all on tables to service_role;
alter default privileges in schema public grant all on sequences to service_role;
