-- Private storage buckets. Deliberately no storage.objects RLS policies for
-- the anon/authenticated roles: all reads and writes go through Edge
-- Functions (create-upload, get-media-batch) using the service role key,
-- which bypasses RLS entirely. Clients only ever see short-lived signed
-- URLs minted by those functions, scoped to exactly the objects they're
-- entitled to (see supabase/functions/*/index.ts).

insert into storage.buckets (id, name, public)
values
  ('media-originals', 'media-originals', false),
  ('media-display', 'media-display', false),
  ('media-thumbnails', 'media-thumbnails', false)
on conflict (id) do nothing;
