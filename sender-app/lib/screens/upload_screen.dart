import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../main.dart';

// Phase 1 thin slice: no pairing UI yet, so we send to this one manually
// provisioned test device (see the claim-device-provisioning walkthrough).
// Replace with a real device picker once pairing (Phase 5) exists.
// Currently pointed at the real kiosk tablet (SM_X200) instead of the
// original fake test device, so uploads actually show up during hardware
// testing.
const _testDeviceId = '228eb5f3-4fe4-4d22-84af-4c98509007e5';

class UploadScreen extends StatefulWidget {
  const UploadScreen({super.key});

  @override
  State<UploadScreen> createState() => _UploadScreenState();
}

class _UploadScreenState extends State<UploadScreen> {
  final _picker = ImagePicker();
  bool _busy = false;
  String? _status;

  Future<void> _pickAndUpload({required ImageSource source}) async {
    final xfile = await _picker.pickImage(source: source, imageQuality: 90);
    if (xfile == null) return;
    await _upload(xfile);
  }

  Future<void> _upload(XFile xfile) async {
    setState(() {
      _busy = true;
      _status = 'Lade hoch...';
    });
    try {
      final file = File(xfile.path);
      final fileSizeBytes = await file.length();
      final extension = xfile.path.split('.').last.toLowerCase();
      final mimeType = _mimeTypeFor(extension);

      final createResponse = await supabase.functions.invoke(
        'create-upload',
        body: {
          'media_type': 'photo',
          'mime_type': mimeType,
          'file_extension': extension,
          'file_size_bytes': fileSizeBytes,
          'device_ids': [_testDeviceId],
        },
      );

      if (createResponse.status != 200) {
        throw Exception('create-upload fehlgeschlagen: ${jsonEncode(createResponse.data)}');
      }
      final createData = createResponse.data as Map<String, dynamic>;
      final storagePath = createData['storage_path'] as String;
      final uploadToken = createData['upload_token'] as String;
      final mediaItemId = createData['media_item_id'] as String;

      await supabase.storage
          .from('media-originals')
          .uploadToSignedUrl(storagePath, uploadToken, file);

      final completeResponse = await supabase.functions.invoke(
        'complete-upload',
        body: {'media_item_id': mediaItemId},
      );
      if (completeResponse.status != 200) {
        throw Exception('complete-upload fehlgeschlagen: ${jsonEncode(completeResponse.data)}');
      }

      setState(() => _status = 'Erfolgreich hochgeladen und dem Test-Tablet zugewiesen!');
    } catch (e) {
      setState(() => _status = 'Fehler: $e');
    } finally {
      setState(() => _busy = false);
    }
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Foto senden'),
        actions: [
          IconButton(onPressed: _logout, icon: const Icon(Icons.logout)),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Angemeldet als: ${supabase.auth.currentUser?.email ?? '-'}'),
            Text(
              'User-ID: ${supabase.auth.currentUser?.id ?? '-'}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _busy ? null : () => _pickAndUpload(source: ImageSource.gallery),
              icon: const Icon(Icons.photo_library),
              label: const Text('Foto aus Galerie wählen'),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy ? null : () => _pickAndUpload(source: ImageSource.camera),
              icon: const Icon(Icons.camera_alt),
              label: const Text('Foto aufnehmen'),
            ),
            const SizedBox(height: 24),
            if (_busy) const Center(child: CircularProgressIndicator()),
            if (_status != null) Text(_status!, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
