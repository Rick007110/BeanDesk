-- BeanDesk: how quiet a place is per part of the day, what users say recomputed when reports
-- go away, and deleting your own account from the app.

-- Quiet and busy reports per part of the day, from the last 12 months:
-- {"morning": {"quiet": 2, "busy": 0}, "weekend": {"quiet": 0, "busy": 3}}. Parts nobody
-- reported on are left out; null without any. Kept here, not computed in the export, because
-- the export runs as anon, who may not read place_reports.
alter table public.place_community add column quiet_by_daypart jsonb;

-- Parts of the day in Dutch time: morning before 12:00, afternoon until 17:00, evening after,
-- and Saturday and Sunday as one part.
create function public.quiet_by_daypart(target bigint)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_object_agg(part, jsonb_build_object('quiet', quiet, 'busy', busy))
    from (
        select
            case
                when extract(isodow from moment) >= 6 then 'weekend'
                when moment::time < time '12:00' then 'morning'
                when moment::time < time '17:00' then 'afternoon'
                else 'evening'
            end as part,
            count(*) filter (where r.quiet) as quiet,
            count(*) filter (where not r.quiet) as busy
        from public.place_reports r
        cross join lateral (select r.reported_at at time zone 'Europe/Amsterdam' as moment) nl
        where r.place_id = target
            and r.quiet is not null
            and r.reported_at > now() - interval '12 months'
        group by 1
    ) parts
$$;

-- What users say about a place, from all its reports: per fact the latest report that names
-- it, the closure reports added up. Without reports the row goes.
create function public.recompute_place_community(target bigint)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not exists (select 1 from public.place_reports r where r.place_id = target) then
        delete from public.place_community where place_id = target;
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

-- The trigger now recomputes instead of patching, so it also works when a report goes.
create or replace function public.apply_report_to_community()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    perform public.recompute_place_community(case when tg_op = 'DELETE' then old.place_id else new.place_id end);
    return null;
end;
$$;

create trigger place_reports_removed_from_community
    after delete on public.place_reports
    for each row execute function public.apply_report_to_community();

revoke execute on function public.quiet_by_daypart(bigint) from public, anon, authenticated;
revoke execute on function public.recompute_place_community(bigint) from public, anon, authenticated;

-- Existing places get their parts of the day.
select public.recompute_place_community(place_id)
from (select distinct place_id from public.place_reports) reported;

-- "Account verwijderen" in the app: the account goes, and with it (on delete cascade) its
-- reports, stars, profile and favourites; the triggers recount what that changes.
create function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    me uuid := auth.uid();
begin
    if me is null then
        raise exception 'not_signed_in' using errcode = '28000';
    end if;
    delete from auth.users where id = me;
end;
$$;

revoke execute on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;

-- The export with the parts of the day added; otherwise unchanged.
create or replace function public.export_place(p public.places, c public.place_community)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'id', p.id,
        'osm_type', p.osm_type,
        'osm_id', p.osm_id,
        'category', p.category,
        'name', p.name,
        'address', p.address,
        'lat', round(extensions.st_y(p.location::extensions.geometry)::numeric, 7),
        'lng', round(extensions.st_x(p.location::extensions.geometry)::numeric, 7),
        'opening_hours', p.opening_hours,
        -- What OSM says; null when the tag is missing or unclear (such as "terminal").
        'osm', jsonb_build_object(
            'wifi', case
                when not (p.osm_tags ? 'internet_access') then null
                when p.osm_tags ->> 'internet_access' = 'no' then false
                when p.osm_tags ->> 'internet_access' in ('yes', 'wifi')
                    or 'wlan' = any (string_to_array(p.osm_tags ->> 'internet_access', ';')) then true
            end,
            -- "customers" means free for whoever buys something, so free for our users.
            'wifi_free', case p.osm_tags ->> 'internet_access:fee'
                when 'no' then true
                when 'customers' then true
                when 'yes' then false
            end,
            'power', case p.osm_tags ->> 'power_supply'
                when 'yes' then true
                when 'no' then false
            end
        ),
        'community', case when c.place_id is not null then jsonb_build_object(
            'wifi', c.wifi,
            'power', c.power,
            'quiet', c.quiet,
            'laptop_policy', c.laptop_policy,
            'closed_reports', c.closed_reports,
            'last_report_at', c.last_report_at,
            'quiet_by_daypart', c.quiet_by_daypart
        ) end,
        -- Stars from users: the average (one decimal) and how many gave one; null without any.
        'rating', (
            select jsonb_build_object('average', round(s.rating_sum::numeric / s.rating_count, 1), 'count', s.rating_count)
            from public.place_rating_summary s
            where s.place_id = p.id and s.rating_count > 0
        ),
        'updated_at', greatest(
            p.updated_at,
            c.updated_at,
            (select s.updated_at from public.place_rating_summary s where s.place_id = p.id)
        )
    )
$$;
