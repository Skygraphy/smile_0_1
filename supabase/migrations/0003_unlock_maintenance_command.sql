-- Adds a remote command that briefly exits Lock Task + restores normal
-- system UI on a kiosk device for legitimate maintenance (reaching Settings,
-- authorizing USB debugging) without ever needing a factory reset just to
-- fix a bug. Deliberately server-triggered only (there is still no on-device
-- way to request this) and self-expiring on the device side -- see
-- MainActivity's handling of this command type.
alter table remote_commands drop constraint remote_commands_command_type_check;
alter table remote_commands add constraint remote_commands_command_type_check
  check (command_type in ('reset_to_policy', 'reboot', 'force_update', 'clear_media_cache', 'refresh_policy', 'unlock_maintenance'));
