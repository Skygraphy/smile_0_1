import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../main.dart';

// A group the current user belongs to (tenant_members row + tenant name).
class _TenantOption {
  final String id;
  final String name;
  const _TenantOption(this.id, this.name);
}

// A device the current user is paired to send to within the selected group
// (device_senders row + device name -- the free-text device name doubles as
// the "person" label per the plan, e.g. "Opas Tablet").
class _DeviceOption {
  final String id;
  final String name;
  const _DeviceOption(this.id, this.name);
}

// State kept between retry attempts once create-upload has already
// succeeded, so a flaky connection never causes a duplicate media_items row:
// retrying re-tries only the step that actually failed (the byte transfer or
// the completion call), reusing the same signed upload token and media item.
class _PendingUpload {
  final String mediaItemId;
  final String storagePath;
  final String uploadToken;
  final File file;
  bool storageUploadDone = false;

  _PendingUpload({
    required this.mediaItemId,
    required this.storagePath,
    required this.uploadToken,
    required this.file,
  });
}

class UploadScreen extends StatefulWidget {
  const UploadScreen({super.key});

  @override
  State<UploadScreen> createState() => _UploadScreenState();
}

class _UploadScreenState extends State<UploadScreen> {
  final _picker = ImagePicker();
  bool _busy = false;
  String? _status;

  bool _loadingRecipients = true;
  String? _recipientsError;
  List<_TenantOption> _tenants = [];
  String? _selectedTenantId;
  List<_DeviceOption> _devices = [];
  final Set<String> _selectedDeviceIds = {};

  @override
  void initState() {
    super.initState();
    _loadTenants();
  }

  Future<void> _loadTenants() async {
    setState(() {
      _loadingRecipients = true;
      _recipientsError = null;
    });
    try {
      final uid = supabase.auth.currentUser!.id;
      final rows = await supabase.from('tenant_members').select('tenant_id, tenants(name)').eq('user_id', uid);
      final tenants = (rows as List).map((row) {
        final tenant = row['tenants'] as Map<String, dynamic>?;
        return _TenantOption(row['tenant_id'] as String, (tenant?['name'] as String?) ?? 'Gruppe');
      }).toList();

      setState(() {
        _tenants = tenants;
        _selectedTenantId = tenants.isNotEmpty ? tenants.first.id : null;
      });

      final tenantId = _selectedTenantId;
      if (tenantId != null) {
        await _loadDevices(tenantId);
      } else {
        setState(() => _loadingRecipients = false);
      }
    } catch (e) {
      setState(() {
        _recipientsError = 'Gruppen konnten nicht geladen werden: $e';
        _loadingRecipients = false;
      });
    }
  }

  Future<void> _loadDevices(String tenantId) async {
    setState(() {
      _loadingRecipients = true;
      _recipientsError = null;
    });
    try {
      final uid = supabase.auth.currentUser!.id;
      final rows = await supabase
          .from('device_senders')
          .select('device_id, devices(id, name)')
          .eq('tenant_id', tenantId)
          .eq('user_id', uid);
      final devices = (rows as List).map((row) {
        final device = row['devices'] as Map<String, dynamic>?;
        return _DeviceOption(row['device_id'] as String, (device?['name'] as String?) ?? 'Gerät');
      }).toList();

      setState(() {
        _devices = devices;
        // Default: everyone in the group, matching the plan's "Alle in der
        // Gruppe" default -- a person sending a photo usually wants
        // everyone paired to see it, not just one device.
        _selectedDeviceIds
          ..clear()
          ..addAll(devices.map((d) => d.id));
        _loadingRecipients = false;
      });
    } catch (e) {
      setState(() {
        _recipientsError = 'Geräte konnten nicht geladen werden: $e';
        _loadingRecipients = false;
      });
    }
  }

  void _onTenantChanged(String? tenantId) {
    if (tenantId == null || tenantId == _selectedTenantId) return;
    setState(() => _selectedTenantId = tenantId);
    _loadDevices(tenantId);
  }

  void _toggleDevice(String deviceId, bool selected) {
    setState(() {
      if (selected) {
        _selectedDeviceIds.add(deviceId);
      } else {
        _selectedDeviceIds.remove(deviceId);
      }
    });
  }

  void _selectAllDevices() {
    setState(() => _selectedDeviceIds.addAll(_devices.map((d) => d.id)));
  }

  _PendingUpload? _pendingUpload;

  Future<void> _pickAndUpload({required ImageSource source}) async {
    final xfile = await _picker.pickImage(source: source, imageQuality: 90);
    if (xfile == null) return;
    await _startUpload(File(xfile.path));
  }

  // Retries a transient failure with backoff (2s, 4s). Deliberately small --
  // this covers a brief WiFi/mobile-data blip, not an offline queue; a
  // longer outage surfaces as a failed status with the retry button below
  // rather than the app hanging on a spinner.
  Future<T> _withRetry<T>(Future<T> Function() action, {int maxAttempts = 3}) async {
    var attempt = 0;
    while (true) {
      attempt++;
      try {
        return await action();
      } catch (e) {
        if (attempt >= maxAttempts) rethrow;
        await Future.delayed(Duration(seconds: attempt * 2));
      }
    }
  }

  Future<Map<String, dynamic>> _createUpload(File file) async {
    final fileSizeBytes = await file.length();
    final extension = file.path.split('.').last.toLowerCase();
    final mimeType = _mimeTypeFor(extension);
    final response = await supabase.functions.invoke(
      'create-upload',
      body: {
        'media_type': 'photo',
        'mime_type': mimeType,
        'file_extension': extension,
        'file_size_bytes': fileSizeBytes,
        'device_ids': _selectedDeviceIds.toList(),
      },
    );
    if (response.status != 200) {
      throw Exception('create-upload fehlgeschlagen: ${jsonEncode(response.data)}');
    }
    return response.data as Map<String, dynamic>;
  }

  Future<void> _completeUpload(String mediaItemId) async {
    final response = await supabase.functions.invoke('complete-upload', body: {'media_item_id': mediaItemId});
    if (response.status != 200) {
      throw Exception('complete-upload fehlgeschlagen: ${jsonEncode(response.data)}');
    }
  }

  Future<void> _startUpload(File file) async {
    if (_selectedDeviceIds.isEmpty) {
      setState(() => _status = 'Bitte mindestens ein Gerät auswählen.');
      return;
    }
    setState(() {
      _busy = true;
      _status = 'Lade hoch...';
    });
    try {
      // create-upload itself is not retried past the outer attempt: it
      // creates a fresh media_items row, so retrying it on top of a partial
      // success would leave duplicate/orphaned rows. Only the idempotent
      // steps below (same signed token, same media item) get retried.
      final createData = await _createUpload(file);
      _pendingUpload = _PendingUpload(
        mediaItemId: createData['media_item_id'] as String,
        storagePath: createData['storage_path'] as String,
        uploadToken: createData['upload_token'] as String,
        file: file,
      );
      await _finishPendingUpload();
    } catch (e) {
      setState(() => _status = 'Fehler: $e');
    } finally {
      setState(() => _busy = false);
    }
  }

  // Runs (or resumes) the two steps after create-upload has already
  // succeeded: the actual byte transfer, then marking the item ready. Each
  // is retried independently and only re-run if it hasn't already
  // succeeded, so pressing "Erneut versuchen" never re-uploads a file that
  // already made it to storage.
  Future<void> _finishPendingUpload() async {
    final pending = _pendingUpload;
    if (pending == null) return;
    setState(() {
      _busy = true;
      _status = 'Lade hoch...';
    });
    try {
      if (!pending.storageUploadDone) {
        await _withRetry(
          () => supabase.storage.from('media-originals').uploadToSignedUrl(
                pending.storagePath,
                pending.uploadToken,
                pending.file,
              ),
        );
        pending.storageUploadDone = true;
      }
      setState(() => _status = 'Schließe ab...');
      await _withRetry(() => _completeUpload(pending.mediaItemId));

      setState(() {
        _status = 'Erfolgreich an ${_selectedDeviceIds.length} Gerät(e) gesendet!';
        _pendingUpload = null;
      });
    } catch (e) {
      setState(() => _status = 'Fehler: $e');
    } finally {
      setState(() => _busy = false);
    }
  }

  void _discardPendingUpload() {
    setState(() {
      _pendingUpload = null;
      _status = null;
    });
  }

  String _mimeTypeFor(String extension) {
    switch (extension) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      default:
        return 'application/octet-stream';
    }
  }

  Future<void> _logout() async {
    await supabase.auth.signOut();
  }

  Widget _buildRecipientPicker() {
    if (_loadingRecipients) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (_recipientsError != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(_recipientsError!, style: const TextStyle(color: Colors.red)),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: _loadTenants, child: const Text('Erneut versuchen')),
        ],
      );
    }
    if (_tenants.isEmpty) {
      return const Text('Du bist noch keiner Gruppe zugeordnet.');
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_tenants.length > 1) ...[
          DropdownButtonFormField<String>(
            initialValue: _selectedTenantId,
            decoration: const InputDecoration(labelText: 'Gruppe'),
            items: _tenants
                .map((t) => DropdownMenuItem(value: t.id, child: Text(t.name)))
                .toList(),
            onChanged: _onTenantChanged,
          ),
          const SizedBox(height: 12),
        ],
        if (_devices.isEmpty)
          const Text('In dieser Gruppe sind dir noch keine Geräte zugeordnet.')
        else ...[
          Row(
            children: [
              Text('An wen senden?', style: Theme.of(context).textTheme.labelLarge),
              const Spacer(),
              TextButton(onPressed: _selectAllDevices, child: const Text('Alle')),
            ],
          ),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: _devices
                .map(
                  (device) => FilterChip(
                    label: Text(device.name),
                    selected: _selectedDeviceIds.contains(device.id),
                    onSelected: (selected) => _toggleDevice(device.id, selected),
                  ),
                )
                .toList(),
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final hasPending = _pendingUpload != null;
    final canSend = !_busy && !hasPending && _selectedDeviceIds.isNotEmpty;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Foto senden'),
        actions: [
          IconButton(onPressed: _logout, icon: const Icon(Icons.logout)),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadTenants,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Angemeldet als: ${supabase.auth.currentUser?.email ?? '-'}'),
              const SizedBox(height: 16),
              _buildRecipientPicker(),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: canSend ? () => _pickAndUpload(source: ImageSource.gallery) : null,
                icon: const Icon(Icons.photo_library),
                label: const Text('Foto aus Galerie wählen'),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: canSend ? () => _pickAndUpload(source: ImageSource.camera) : null,
                icon: const Icon(Icons.camera_alt),
                label: const Text('Foto aufnehmen'),
              ),
              const SizedBox(height: 24),
              if (_busy) const Center(child: CircularProgressIndicator()),
              if (_status != null) Text(_status!, textAlign: TextAlign.center),
              if (hasPending && !_busy) ...[
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: _finishPendingUpload,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Erneut versuchen'),
                ),
                const SizedBox(height: 8),
                TextButton(onPressed: _discardPendingUpload, child: const Text('Verwerfen')),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
