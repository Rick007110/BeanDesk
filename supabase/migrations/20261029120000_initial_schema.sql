-- BeanDesk: initial schema.
--
-- OSM data (places) and user data (place_reports, place_community, favorites) live in
-- separate tables, so the weekly import never overwrites user contributions.

create schema if not exists extensions;
create extension if not exists postgis with schema extensions;

create type public.place_category as enum ('cafe', 'library', 'coworking');
create type public.wifi_status as enum ('none', 'yes', 'slow', 'fast');
create type public.power_level as enum ('none', 'few', 'many');
create type public.laptop_policy as enum ('allowed', 'not_on_weekends', 'not_allowed');

-- Places from OpenStreetMap. Only the import script (service role) writes here.
create table public.places (
    id bigint generated always as identity primary key,
    osm_type text not null check (osm_type in ('node', 'way', 'relation')),
    osm_id bigint not null,
    city text not null,
    category public.place_category not null,
    name text not null,
    address text,
    location extensions.geography(point, 4326) not null,
    opening_hours text,
    osm_tags jsonb not null default '{}'::jsonb,
    -- Set by hand (dashboard) for places that are in OSM but are no laptop spot, such as a pub tagged as cafe.
    hidden boolean not null default false,
    -- Set by the import when a place disappears from OSM; the row stays so its reports are kept.
    deleted_at timestamptz,
    updated_at timestamptz not null default now(),
    unique (osm_type, osm_id)
);

create index places_location_idx on public.places using gist (location);
create index places_city_updated_idx on public.places (city, updated_at);

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    display_name text,
    -- Phase 2: weighs reports; new and anonymous accounts count less.
    -- Whether an account is anonymous is already in auth.users.is_anonymous.
    trust_score real not null default 0,
    created_at timestamptz not null default now()
);

-- One row per tap on "Klopt dit nog?". At least one fact must be reported.
create table public.place_reports (
    id bigint generated always as identity primary key,
    place_id bigint not null references public.places (id) on delete cascade,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    reported_at timestamptz not null default now(),
    wifi public.wifi_status,
    power public.power_level,
    -- Quiet at the time of reporting; phase 2 groups this per part of the day.
    quiet boolean,
    laptop_policy public.laptop_policy,
    permanently_closed boolean not null default false,
    check (
        wifi is not null or power is not null or quiet is not null
        or laptop_policy is not null or permanently_closed
    )
);

create index place_reports_place_idx on public.place_reports (place_id, reported_at desc);
create index place_reports_user_idx on public.place_reports (user_id, reported_at desc);

-- What users currently say about a place, kept up to date by a trigger on place_reports.
-- For now the latest report per fact wins; phase 2 replaces this with the weighted
-- laptop score (freshness and trust score, see the plan).
create table public.place_community (
    place_id bigint primary key references public.places (id) on delete cascade,
    wifi public.wifi_status,
    power public.power_level,
    quiet boolean,
    laptop_policy public.laptop_policy,
    closed_reports integer not null default 0,
    last_report_at timestamptz,
    updated_at timestamptz not null default now()
);

create index place_community_updated_idx on public.place_community (updated_at);

create table public.favorites (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    place_id bigint not null references public.places (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, place_id)
);

-- Privileges. Supabase grants everything to anon and authenticated by default;
-- narrow that down so columns such as trust_score and reported_at cannot be set by users.
-- Anonymous sign-ins use the authenticated role as well.
revoke all on public.places, public.place_community, public.place_reports, public.profiles, public.favorites
    from anon, authenticated;
grant select on public.places, public.place_community to anon, authenticated;
grant select on public.place_reports to authenticated;
grant insert (place_id, wifi, power, quiet, laptop_policy, permanently_closed) on public.place_reports to authenticated;
grant select on public.profiles to authenticated;
grant update (display_name) on public.profiles to authenticated;
grant select, insert, delete on public.favorites to authenticated;

alter table public.places enable row level security;
alter table public.place_community enable row level security;
alter table public.place_reports enable row level security;
alter table public.profiles enable row level security;
alter table public.favorites enable row level security;

create policy "Places are public" on public.places
    for select to anon, authenticated using (true);

create policy "Community facts are public" on public.place_community
    for select to anon, authenticated using (true);

create policy "Users add their own reports" on public.place_reports
    for insert to authenticated with check (user_id = (select auth.uid()));

create policy "Users see their own reports" on public.place_reports
    for select to authenticated using (user_id = (select auth.uid()));

create policy "Users see their own profile" on public.profiles
    for select to authenticated using (id = (select auth.uid()));

create policy "Users update their own profile" on public.profiles
    for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy "Users manage their own favorites" on public.favorites
    for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- Every new account (anonymous or not) gets a profile.
create function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (id) values (new.id);
    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- Daily report limit per account; anonymous accounts get a lower limit.
-- Starting values: adjust once there is real usage.
create function public.enforce_report_rate_limit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    is_anonymous boolean;
    daily_limit integer;
    recent integer;
begin
    select coalesce(u.is_anonymous, false) into is_anonymous from auth.users u where u.id = new.user_id;
    daily_limit := case when is_anonymous then 20 else 60 end;
    select count(*) into recent
    from public.place_reports r
    where r.user_id = new.user_id and r.reported_at > now() - interval '1 day';

    if recent >= daily_limit then
        raise exception 'rate_limit: too many reports in the last 24 hours' using errcode = 'P0001';
    end if;
    return new;
end;
$$;

create trigger place_reports_rate_limit
    before insert on public.place_reports
    for each row execute function public.enforce_report_rate_limit();

create function public.apply_report_to_community()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.place_community as c
        (place_id, wifi, power, quiet, laptop_policy, closed_reports, last_report_at, updated_at)
    values
        (new.place_id, new.wifi, new.power, new.quiet, new.laptop_policy,
         new.permanently_closed::integer, new.reported_at, now())
    on conflict (place_id) do update set
        wifi = coalesce(excluded.wifi, c.wifi),
        power = coalesce(excluded.power, c.power),
        quiet = coalesce(excluded.quiet, c.quiet),
        laptop_policy = coalesce(excluded.laptop_policy, c.laptop_policy),
        closed_reports = c.closed_reports + excluded.closed_reports,
        last_report_at = excluded.last_report_at,
        updated_at = now();
    return new;
end;
$$;

create trigger place_reports_to_community
    after insert on public.place_reports
    for each row execute function public.apply_report_to_community();

-- The import script uploads one JSON file per city here; the app downloads it.
insert into storage.buckets (id, name, public)
values ('city-exports', 'city-exports', true)
on conflict (id) do nothing;
