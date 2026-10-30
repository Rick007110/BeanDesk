-- BeanDesk: only what changed since the app's last download, removing anonymous accounts that
-- never reported anything, and seed reports from visits.

-- When the last report about a place goes (with its account), the place's export changes too:
-- its updated_at tells the app's next download of changes. Otherwise as before.
create or replace function public.recompute_place_community(target bigint)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not exists (select 1 from public.place_reports r where r.place_id = target) then
        delete from public.place_community where place_id = target;
        if found then
            update public.places set updated_at = now() where id = target;
        end if;
        return;
    end if;

    insert into public.place_community as c
        (place_id, wifi, power, quiet, laptop_policy, closed_reports, last_report_at, quiet_by_daypart, updated_at)
    select
        target,
        (select r.wifi from public.place_reports r
            where r.place_id = target and r.wifi is not null order by r.reported_at desc, r.id desc limit 1),
        (select r.power from public.place_reports r
            where r.place_id = target and r.power is not null order by r.reported_at desc, r.id desc limit 1),
        (select r.quiet from public.place_reports r
            where r.place_id = target and r.quiet is not null order by r.reported_at desc, r.id desc limit 1),
        (select r.laptop_policy from public.place_reports r
            where r.place_id = target and r.laptop_policy is not null order by r.reported_at desc, r.id desc limit 1),
        (select count(*) from public.place_reports r where r.place_id = target and r.permanently_closed),
        (select max(r.reported_at) from public.place_reports r where r.place_id = target),
        public.quiet_by_daypart(target),
        now()
    on conflict (place_id) do update set
        wifi = excluded.wifi,
        power = excluded.power,
        quiet = excluded.quiet,
        laptop_policy = excluded.laptop_policy,
        closed_reports = excluded.closed_reports,
        last_report_at = excluded.last_report_at,
        quiet_by_daypart = excluded.quiet_by_daypart,
        updated_at = excluded.updated_at;
end;
$$;

revoke execute on function public.recompute_place_community(bigint) from public, anon, authenticated;

-- What changed in a city since p_since, the generated_at of the app's last download: the places
-- whose export changed (their place, reports or stars), and the ids of places that are gone
-- (closed in OpenStreetMap or hidden by hand). The same format as city_export, plus "since" and
-- "removed". Five minutes overlap: a report whose transaction started before p_since but
-- committed after carries an older timestamp. Sending a place twice does no harm.
create function public.city_changes(p_city text, p_since timestamptz)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
    with since as (
        select p_since - interval '5 minutes' as at
    ),
    changed as (
        select p.id, public.export_place(p, c) as place
        from public.places p
        cross join since
        left join public.place_community c on c.place_id = p.id
        left join public.place_rating_summary s on s.place_id = p.id
        where p.city = p_city
            and p.deleted_at is null
            and not p.hidden
            and greatest(p.updated_at, c.updated_at, s.updated_at) > since.at
    ),
    removed as (
        select p.id
        from public.places p
        cross join since
        where p.city = p_city
            and (p.deleted_at is not null or p.hidden)
            and p.updated_at > since.at
    )
    select jsonb_build_object(
        'version', 1,
        'city', ci.slug,
        'timezone', ci.timezone,
        'generated_at', now(),
        'since', p_since,
        'attribution', '© OpenStreetMap-bijdragers',
        'places', coalesce((select jsonb_agg(ch.place order by ch.id) from changed ch), '[]'::jsonb),
        'removed', coalesce((select jsonb_agg(r.id order by r.id) from removed r), '[]'::jsonb)
    )
    from public.cities ci
    where ci.slug = p_city
$$;

grant execute on function public.city_changes(text, timestamptz) to anon, authenticated;

-- Hiding a place by hand counts as a change, so the app's next download of changes drops it.
create function public.touch_place_on_hide()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.hidden is distinct from old.hidden then
        new.updated_at := now();
    end if;
    return new;
end;
$$;

create trigger places_touch_on_hide
    before update of hidden on public.places
    for each row execute function public.touch_place_on_hide();

revoke execute on function public.touch_place_on_hide() from public, anon, authenticated;

-- An anonymous account is made at someone's first report. When that report never arrived
-- (no network, the daily limit), an empty account is left behind; after p_older_than it goes.
-- Accounts with reports or stars stay. Returns how many went.
create function public.delete_idle_anonymous_accounts(p_older_than interval default interval '30 days')
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    deleted integer;
begin
    delete from auth.users u
    where u.is_anonymous
        and u.created_at < now() - p_older_than
        and not exists (select 1 from public.place_reports r where r.user_id = u.id)
        and not exists (select 1 from public.place_ratings r where r.user_id = u.id);
    get diagnostics deleted = row_count;
    return deleted;
end;
$$;

revoke execute on function public.delete_idle_anonymous_accounts(interval) from public, anon, authenticated;
grant execute on function public.delete_idle_anonymous_accounts(interval) to service_role;

-- Seeding: what someone saw on a visit, entered with importer/ (seed-import) as reports of one
-- account that is not a person. reported_at is the time of the visit, so "laatst bevestigd" and
-- the parts of the day are right.
create function public.seed_account_id()
returns uuid
language sql
immutable
set search_path = ''
as $$
    select '00000000-0000-4000-8000-00000000bea5'::uuid
$$;

-- The daily limit is for people. Seed reports are checked by hand before they go in, and users
-- cannot report as the seed account: the insert policy wants their own id.
create or replace function public.enforce_report_rate_limit()
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
    if new.user_id = public.seed_account_id() then
        return new;
    end if;
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

-- p_rows: [{"place_id": 1, "visited_at": "2026-11-02T10:30:00+01:00", "wifi": "fast", ...}]
-- Returns how many reports went in.
create function public.seed_reports(p_rows jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    inserted integer;
begin
    insert into auth.users (id, is_anonymous)
    values (public.seed_account_id(), false)
    on conflict (id) do nothing;

    insert into public.place_reports
        (place_id, user_id, reported_at, wifi, power, quiet, laptop_policy, permanently_closed)
    select
        (row ->> 'place_id')::bigint,
        public.seed_account_id(),
        (row ->> 'visited_at')::timestamptz,
        (row ->> 'wifi')::public.wifi_status,
        (row ->> 'power')::public.power_level,
        (row ->> 'quiet')::boolean,
        (row ->> 'laptop_policy')::public.laptop_policy,
        coalesce((row ->> 'permanently_closed')::boolean, false)
    from jsonb_array_elements(p_rows) as row;
    get diagnostics inserted = row_count;
    return inserted;
end;
$$;

revoke execute on function public.enforce_report_rate_limit() from public, anon, authenticated;
revoke execute on function public.seed_reports(jsonb) from public, anon, authenticated;
grant execute on function public.seed_reports(jsonb) to service_role;

-- On Supabase: every night at 04:43 UTC.
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule(
            'beandesk-delete-idle-anonymous-accounts',
            '43 4 * * *',
            $job$select public.delete_idle_anonymous_accounts()$job$
        );
    end if;
end;
$$;
