import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../main.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _emailController = TextEditingController();
  final _codeController = TextEditingController();

  bool _codeSent = false;
  bool _loading = false;
  String? _error;

  Future<void> _sendCode() async {
    final email = _emailController.text.trim();
    if (email.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await supabase.auth.signInWithOtp(email: email);
      setState(() => _codeSent = true);
    } catch (e) {
      setState(() => _error = 'Code konnte nicht gesendet werden: $e');
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _verifyCode() async {
    final email = _emailController.text.trim();
    final code = _codeController.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await supabase.auth.verifyOTP(
        type: OtpType.email,
        token: code,
        email: email,
      );
      // On success, AuthGate's onAuthStateChange listener switches screens automatically.
    } catch (e) {
      setState(() => _error = 'Code ungültig oder abgelaufen: $e');
    } finally {
      setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _emailController.dispose();
    _codeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Anmelden')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _emailController,
              enabled: !_codeSent,
              keyboardType: TextInputType.emailAddress,
              decoration: const InputDecoration(labelText: 'E-Mail-Adresse'),
            ),
            const SizedBox(height: 16),
            if (_codeSent) ...[
              TextField(
                controller: _codeController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: '6-stelliger Code aus der E-Mail'),
              ),
              const SizedBox(height: 16),
            ],
            if (_error != null) ...[
              Text(_error!, style: const TextStyle(color: Colors.red)),
              const SizedBox(height: 16),
            ],
            FilledButton(
              onPressed: _loading ? null : (_codeSent ? _verifyCode : _sendCode),
              child: Text(_loading ? 'Bitte warten...' : (_codeSent ? 'Anmelden' : 'Code senden')),
            ),
          ],
        ),
      ),
    );
  }
}
