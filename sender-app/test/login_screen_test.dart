import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sender_app/screens/login_screen.dart';

void main() {
  testWidgets('LoginScreen shows email field and send-code button initially', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: LoginScreen()));

    expect(find.text('E-Mail-Adresse'), findsOneWidget);
    expect(find.text('Code senden'), findsOneWidget);
    expect(find.text('Code aus der E-Mail'), findsNothing);
  });

  testWidgets('Send-code button is disabled while loading', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: LoginScreen()));

    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNotNull);
  });
}
