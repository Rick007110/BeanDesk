-- BeanDesk: stars. Users give a place 1 to 5 stars as a laptop spot, one rating per user per
-- place (a new one replaces the old). The export shows the average and the number of ratings.

create table public.place_ratings (
    place_id bigint not null references public.places (id) on delete cascade,
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    rating smallint not null check (rating between 1 and 5),
    rated_at timestamptz not null default now(),
    primary key (place_id, user_id)
);

-- Kept up to date by a trigger, so the export stays cheap and who rated what stays private.
create table public.place_rating_summary (
    place_id bigint primary key references public.places (id) on delete cascade,
    rating_sum integer not null,
    rating_count integer not null,
    updated_at timestamptz not null default now()
);

revoke all on public.place_ratings, public.place_rating_summary from anon, authenticated;
grant select on public.place_ratings to authenticated;
-- An upsert from the app (on conflict place_id, user_id) updates the columns it sends.
grant insert (place_id, rating), update (place_id, rating) on public.place_ratings to authenticated;
grant select on public.place_rating_summary to anon, authenticated;

alter table public.place_ratings enable row level security;
alter table public.place_rating_summary enable row level security;

create policy "Users rate as themselves" on public.place_ratings
    for insert to authenticated with check (user_id = (select auth.uid()));

create policy "Users change their own ratings" on public.place_ratings
    for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy "Users see their own ratings" on public.place_ratings
    for select to authenticated using (user_id = (select auth.uid()));

create policy "Rating summaries are public" on public.place_rating_summary
    for select to anon, authenticated using (true);

create function public.touch_rating()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.rated_at := now();
    return new;
end;
$$;

create trigger place_ratings_touch
    before update on public.place_ratings
    for each row execute function public.touch_rating();

-- Recounts the places a rating was added to, changed on or removed from.
create function public.apply_rating_to_summary()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    target bigint;
begin
    for target in
        select distinct x from unnest(array[
            case when tg_op <> 'INSERT' then old.place_id end,
            case when tg_op <> 'DELETE' then new.place_id end
        ]) as x
        where x is not null
    loop
        insert into public.place_rating_summary as s (place_id, rating_sum, rating_count, updated_at)
        select target, coalesce(sum(r.rating), 0), count(*), now()
        from public.place_ratings r
        where r.place_id = target
        on conflict (place_id) do update set
            rating_sum = excluded.rating_sum,
            rating_count = excluded.rating_count,
            updated_at = excluded.updated_at;
    end loop;
    return null;
end;
$$;

create trigger place_ratings_summary
    after insert or update or delete on public.place_ratings
    for each row execute function public.apply_rating_to_summary();

revoke execute on function public.touch_rating() from public, anon, authenticated;
revoke execute on function public.apply_rating_to_summary() from public, anon, authenticated;

-- The export with the stars added; otherwise unchanged.
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
            'last_report_at', c.last_report_at
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

