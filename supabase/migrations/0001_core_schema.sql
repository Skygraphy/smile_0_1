-- Core schema for the family photo/video kiosk platform.
-- Multi-tenant: every tenant-scoped table carries tenant_id and is isolated via RLS.

create extension if not exists "pgcrypto";

-- =========================================================================
-- Tenancy & identity
-- =========================================================================

create table tenants (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  plan text not null default 'family',
  status text not null default 'active' check (status in ('active', 'suspended')),
  created_at timestamptz not null default now()
);

create table tenant_members (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner', 'admin', 'sender')),
  created_at timestamptz not null default now(),
  unique (tenant_id, user_id)
);

-- Internal staff (support/ops), not tied to a single tenant. Kept separate from
-- tenant_members so no tenant-scoped role ever doubles as a cross-tenant role.
create table staff_members (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('support', 'ops_admin')),
  created_at timestamptz not null default now(),
  unique (user_id)
);

-- =========================================================================
-- Devices & policy
-- =========================================================================

create table devices (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  name text not null,
  serial_number text,
  android_id text,
  fcm_token text,
  enrollment_status text not null default 'pending'
    check (enrollment_status in ('pending', 'provisioned', 'active', 'deactivated')),
  current_app_version text,
  current_policy_version int not null default 0,
  last_seen_at timestamptz,
  last_compliance_check_at timestamptz,
  last_compliance_state text
    check (last_compliance_state in ('compliant', 'drift_detected', 'repaired', 'unknown')),
  battery_level int,
  is_charging boolean,
  created_at timestamptz not null default now()
);

create table device_policies (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null unique references devices(id) on delete cascade,
  display_mode text not null default 'slideshow' check (display_mode in ('slideshow', 'manual')),
  slideshow_interval_seconds int not null default 8,
  compliance_check_interval_minutes int not null default 15,
  allowed_package_name text not null default 'com.smile.kiosk',
  kiosk_app_min_version text,
  screen_brightness int,
  volume_level int,
  wifi_ssid_expected text,
  -- Full local offline cache is the default (tablets must keep showing all
  -- assigned media with no connectivity, e.g. when carried out to present).
  -- Null = unlimited/no eviction. Only set this as a safety valve on
  -- storage-constrained hardware; when set, oldest-by-sort_order items are
  -- evicted first once the cap is hit.
  max_local_cache_gb numeric,
  policy_version int not null default 1,
  updated_at timestamptz not null default now(),
  updated_by uuid references tenant_members(id)
);

-- Append-only compliance-check history. Pruned by a scheduled job (see Phase 7).
create table device_heartbeats (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  checked_at timestamptz not null default now(),
  compliance_state text not null
    check (compliance_state in ('compliant', 'drift_detected', 'repaired', 'unknown')),
  drift_details jsonb,
  app_version text,
  battery_level int,
  is_charging boolean,
  network_type text,
  created_at timestamptz not null default now()
);

-- =========================================================================
-- Pairing
-- =========================================================================

create table pairing_codes (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  device_id uuid references devices(id) on delete cascade,
  code text not null unique,
  code_type text not null check (code_type in ('device_provisioning', 'sender_invite')),
  expires_at timestamptz not null,
  max_uses int not null default 1,
  use_count int not null default 0,
  created_by uuid references tenant_members(id),
  created_at timestamptz not null default now()
);

create table device_senders (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  paired_at timestamptz not null default now(),
  unique (device_id, user_id)
);

-- =========================================================================
-- Media
-- =========================================================================

create table media_items (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  media_type text not null check (media_type in ('photo', 'video')),
  storage_path_original text not null,
  storage_path_display text,
  storage_path_thumbnail text,
  mime_type text,
  duration_seconds numeric,
  width int,
  height int,
  file_size_bytes bigint,
  processing_status text not null default 'uploaded'
    check (processing_status in ('uploaded', 'processing', 'ready', 'failed')),
  caption text,
  created_at timestamptz not null default now()
);

create table media_recipients (
  id uuid primary key default gen_random_uuid(),
  media_item_id uuid not null references media_items(id) on delete cascade,
  device_id uuid not null references devices(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  delivered_at timestamptz,
  viewed_at timestamptz,
  sort_order bigint not null default (extract(epoch from now()) * 1000)::bigint,
  hidden_at timestamptz,
  created_at timestamptz not null default now(),
  unique (media_item_id, device_id)
);

-- =========================================================================
-- Remote management
-- =========================================================================

create table remote_commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  command_type text not null
    check (command_type in ('reset_to_policy', 'reboot', 'force_update', 'clear_media_cache', 'refresh_policy')),
  payload jsonb,
  status text not null default 'pending'
    check (status in ('pending', 'delivered', 'acknowledged', 'completed', 'failed', 'expired')),
  requested_by uuid references tenant_members(id),
  requested_at timestamptz not null default now(),
  delivered_at timestamptz,
  completed_at timestamptz,
  result_detail jsonb,
  expires_at timestamptz not null default (now() + interval '24 hours')
);

create table app_releases (
  id uuid primary key default gen_random_uuid(),
  version_name text not null,
  version_code int not null unique,
  apk_storage_path text,
  release_notes text,
  rollout_status text not null default 'draft'
    check (rollout_status in ('draft', 'staged', 'production', 'rolled_back')),
  created_at timestamptz not null default now()
);

-- =========================================================================
-- Indexes
-- =========================================================================

create index idx_tenant_members_user on tenant_members(user_id);
create index idx_devices_tenant on devices(tenant_id);
create index idx_device_heartbeats_device_checked on device_heartbeats(device_id, checked_at desc);
create index idx_pairing_codes_tenant on pairing_codes(tenant_id);
create index idx_device_senders_user on device_senders(user_id);
create index idx_media_items_tenant on media_items(tenant_id);
create index idx_media_items_sender on media_items(sender_id);
create index idx_media_recipients_device_sort on media_recipients(device_id, sort_order) where hidden_at is null;
create index idx_remote_commands_device_status on remote_commands(device_id, status);

-- =========================================================================
-- Row Level Security
-- =========================================================================

alter table tenants enable row level security;
alter table tenant_members enable row level security;
alter table staff_members enable row level security;
alter table devices enable row level security;
alter table device_policies enable row level security;
alter table device_heartbeats enable row level security;
alter table pairing_codes enable row level security;
alter table device_senders enable row level security;
alter table media_items enable row level security;
alter table media_recipients enable row level security;
alter table remote_commands enable row level security;
alter table app_releases enable row level security;

-- Helper: is the current user a member of a given tenant (any role)?
create or replace function is_tenant_member(check_tenant_id uuid)
returns boolean
language sql
security definer
stable
as $$
  select exists (
    select 1 from tenant_members
    where tenant_id = check_tenant_id and user_id = auth.uid()
  );
$$;

-- Helper: is the current user an owner/admin of a given tenant?
create or replace function is_tenant_admin(check_tenant_id uuid)
returns boolean
language sql
security definer
stable
as $$
  select exists (
    select 1 from tenant_members
    where tenant_id = check_tenant_id and user_id = auth.uid() and role in ('owner', 'admin')
  );
$$;

-- Helper: does the current device JWT (device_id claim) match, scoped to its own tenant?
create or replace function is_own_device(check_device_id uuid)
returns boolean
language sql
security definer
stable
as $$
  select coalesce((auth.jwt() ->> 'device_id')::uuid, '00000000-0000-0000-0000-000000000000'::uuid) = check_device_id;
$$;

create or replace function is_staff()
returns boolean
language sql
security definer
stable
as $$
  select exists (select 1 from staff_members where user_id = auth.uid());
$$;

-- tenants: members can read their own tenant; staff can read all.
create policy tenants_select on tenants
  for select using (is_tenant_member(id) or is_staff());
create policy tenants_staff_write on tenants
  for all using (is_staff()) with check (is_staff());

-- tenant_members: members can see their own tenant's membership list; admins manage it.
create policy tenant_members_select on tenant_members
  for select using (is_tenant_member(tenant_id) or is_staff());
create policy tenant_members_admin_write on tenant_members
  for all using (is_tenant_admin(tenant_id) or is_staff())
  with check (is_tenant_admin(tenant_id) or is_staff());

-- staff_members: staff only.
create policy staff_members_staff_only on staff_members
  for all using (is_staff()) with check (is_staff());

-- devices: tenant members can read; admins manage; device itself can read/update its own row.
create policy devices_select on devices
  for select using (is_tenant_member(tenant_id) or is_own_device(id) or is_staff());
create policy devices_admin_write on devices
  for insert with check (is_tenant_admin(tenant_id) or is_staff());
create policy devices_admin_update on devices
  for update using (is_tenant_admin(tenant_id) or is_own_device(id) or is_staff())
  with check (is_tenant_admin(tenant_id) or is_own_device(id) or is_staff());

-- device_policies: tenant members can read; admins write; device can read its own.
create policy device_policies_select on device_policies
  for select using (
    is_tenant_member((select tenant_id from devices where id = device_id))
    or is_own_device(device_id)
    or is_staff()
  );
create policy device_policies_admin_write on device_policies
  for all using (
    is_tenant_admin((select tenant_id from devices where id = device_id)) or is_staff()
  )
  with check (
    is_tenant_admin((select tenant_id from devices where id = device_id)) or is_staff()
  );

-- device_heartbeats: tenant members read; device inserts its own; nobody updates/deletes directly.
create policy device_heartbeats_select on device_heartbeats
  for select using (is_tenant_member(tenant_id) or is_staff());
create policy device_heartbeats_device_insert on device_heartbeats
  for insert with check (is_own_device(device_id) or is_staff());

-- pairing_codes: tenant admins manage; senders can read codes only to redeem via Edge Function
-- (Edge Functions use the service role and bypass RLS, so no broad sender-select policy is needed here).
create policy pairing_codes_admin_all on pairing_codes
  for all using (is_tenant_admin(tenant_id) or is_staff())
  with check (is_tenant_admin(tenant_id) or is_staff());

-- device_senders: tenant members can read; a sender can see their own pairings; admins manage.
create policy device_senders_select on device_senders
  for select using (is_tenant_member(tenant_id) or user_id = auth.uid() or is_staff());
create policy device_senders_admin_write on device_senders
  for all using (is_tenant_admin(tenant_id) or is_staff())
  with check (is_tenant_admin(tenant_id) or is_staff());

-- media_items: a sender can see/create their own uploads; tenant admins can see all in their tenant;
-- the device itself never reads media_items directly (it goes through media_recipients + Edge Function).
create policy media_items_sender_select on media_items
  for select using (sender_id = auth.uid() or is_tenant_admin(tenant_id) or is_staff());
create policy media_items_sender_insert on media_items
  for insert with check (sender_id = auth.uid() and is_tenant_member(tenant_id));

-- media_recipients: a sender may create rows only for devices they're paired with;
-- tenant members can read; device can read/update (delivered_at/viewed_at) its own.
create policy media_recipients_select on media_recipients
  for select using (
    is_tenant_member(tenant_id) or is_own_device(device_id) or is_staff()
  );
create policy media_recipients_sender_insert on media_recipients
  for insert with check (
    exists (
      select 1 from device_senders ds
      where ds.device_id = media_recipients.device_id and ds.user_id = auth.uid()
    )
    and exists (
      select 1 from media_items mi
      where mi.id = media_recipients.media_item_id and mi.sender_id = auth.uid()
    )
  );
create policy media_recipients_device_update on media_recipients
  for update using (is_own_device(device_id) or is_tenant_admin(tenant_id) or is_staff())
  with check (is_own_device(device_id) or is_tenant_admin(tenant_id) or is_staff());

-- remote_commands: tenant admins create/read; device reads its own pending commands and
-- updates status as it processes them.
create policy remote_commands_admin_select on remote_commands
  for select using (is_tenant_admin(tenant_id) or is_own_device(device_id) or is_staff());
create policy remote_commands_admin_insert on remote_commands
  for insert with check (is_tenant_admin(tenant_id) or is_staff());
create policy remote_commands_device_update on remote_commands
  for update using (is_own_device(device_id) or is_tenant_admin(tenant_id) or is_staff())
  with check (is_own_device(device_id) or is_tenant_admin(tenant_id) or is_staff());

-- app_releases: readable by any authenticated tenant member (to show current version info);
-- writable by staff only (this is an internal release-tracking table).
create policy app_releases_select on app_releases
  for select using (auth.role() = 'authenticated' or is_staff());
create policy app_releases_staff_write on app_releases
  for all using (is_staff()) with check (is_staff());
