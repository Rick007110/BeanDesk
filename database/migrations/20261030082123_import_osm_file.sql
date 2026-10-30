-- BeanDesk: all of the Netherlands from one OpenStreetMap extract, so a search no longer waits for
-- the public Overpass servers. The GitHub Action osm-data.yml turns the Geofabrik extract into
-- nl-places.json (importer/beandesk_import/osm_extract.py) and publishes it under the release
-- "osm-nl"; the database fetches and imports it once a month.

-- Imports a file made by osm_extract.py: {"cells": [[lat_index, lng_index], ...], "elements": [...]}.
-- Its cells then count as fetched, with the usual import rules and soft deletes (import_osm_area).
create function public.import_osm_extract(p_data jsonb)
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select public.import_osm_area(
        coalesce(
            (select jsonb_agg(jsonb_build_object('lat_index', c -> 0, 'lng_index', c -> 1))
             from jsonb_array_elements(p_data -> 'cells') as c),
            '[]'::jsonb
        ),
        coalesce(p_data -> 'elements', '[]'::jsonb)
    ) || jsonb_build_object('generated_at', p_data ->> 'generated_at')
$$;

create function public.import_osm_file(
    p_url text default 'https://github.com/Rick007110/BeanDesk/releases/download/osm-nl/nl-places.json'
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    response record;
begin
    -- GitHub answers a release download with a redirect, which the http extension follows.
    perform extensions.http_set_curlopt('CURLOPT_TIMEOUT', '180');
    perform extensions.http_set_curlopt('CURLOPT_USERAGENT', 'BeanDesk-import/0.1 (+https://github.com/Rick007110/BeanDesk)');
    select * into response from extensions.http_get(p_url);
    if response.status <> 200 then
        raise exception 'osm_file_failed: HTTP % for %', response.status, p_url;
    end if;
    return public.import_osm_extract(response.content::jsonb);
end;
$$;

revoke execute on function public.import_osm_extract(jsonb) from public, anon, authenticated;
revoke execute on function public.import_osm_file(text) from public, anon, authenticated;
grant execute on function public.import_osm_extract(jsonb) to service_role;
grant execute on function public.import_osm_file(text) to service_role;

-- The Action publishes a new file on the 1st of the month at 02:23 UTC; import it three hours later.
-- Plain PostgreSQL without pg_cron (tests) skips this.
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule('beandesk-import-netherlands', '23 5 1 * *', $job$select public.import_osm_file()$job$);
    end if;
end;
$$;
