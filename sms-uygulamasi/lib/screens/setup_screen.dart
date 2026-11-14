import 'package:flutter/material.dart';

import '../platform.dart';
import '../store.dart';

/// Kurulum: önce varsayılan SMS uygulaması rolü, sonra izinler.
class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key});

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  bool _permissionDenied = false;

  @override
  void initState() {
    super.initState();
    store.addListener(_onStore);
  }

  @override
  void dispose() {
    store.removeListener(_onStore);
    super.dispose();
  }

  void _onStore() {
    if (mounted) setState(() {});
  }

  Future<void> _requestRole() async {
    await Sms.requestRole();
    await store.refreshStatus();
  }

  Future<void> _requestPermissions() async {
    final granted = await Sms.requestPermissions();
    await store.refreshStatus();
    if (mounted) setState(() => _permissionDenied = !granted);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDefault = store.isDefault == true;
    final permissionsOk = store.permissionsOk;

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 32),
            Icon(Icons.sms_outlined, size: 64, color: theme.colorScheme.primary),
            const SizedBox(height: 16),
            Text(
              'Çift SIM Mesajlar',
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            Text(
              'Mesajlarınızı SIM kartlara göre ayrı ayrı görmek ve göndermek için '
              'iki adımı tamamlayın.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyLarge
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 32),
            _StepCard(
              number: 1,
              done: isDefault,
              title: 'Varsayılan SMS uygulaması',
              description: 'SMS almak, kaydetmek ve göndermek için bu uygulamanın '
                  'varsayılan SMS uygulaması olması gerekir.',
              button: 'Varsayılan SMS uygulaması yap',
              onPressed: isDefault ? null : _requestRole,
            ),
            const SizedBox(height: 16),
            _StepCard(
              number: 2,
              done: permissionsOk,
              title: 'İzinler',
              description: 'SMS, telefon durumu (SIM bilgisi), rehber ve bildirim '
                  'izinleri istenir.',
              button: 'İzinleri ver',
              onPressed: isDefault && !permissionsOk ? _requestPermissions : null,
            ),
            if (_permissionDenied && !permissionsOk) ...[
              const SizedBox(height: 12),
              Text(
                'Gerekli izinler verilmedi. İzin penceresi açılmıyorsa izinleri '
                'uygulama ayarlarından verebilirsiniz.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.error),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: Sms.openAppSettings,
                  child: const Text('Uygulama ayarlarını aç'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.number,
    required this.done,
    required this.title,
    required this.description,
    required this.button,
    required this.onPressed,
  });

  final int number;
  final bool done;
  final String title;
  final String description;
  final String button;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  backgroundColor: done
                      ? SimInfo.sim2Color
                      : theme.colorScheme.primaryContainer,
                  child: done
                      ? const Icon(Icons.check, size: 18, color: Colors.white)
                      : Text('$number', style: theme.textTheme.labelLarge),
                ),
                const SizedBox(width: 12),
                Expanded(child: Text(title, style: theme.textTheme.titleMedium)),
              ],
            ),
            const SizedBox(height: 8),
            Text(description, style: theme.textTheme.bodyMedium),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: onPressed,
              child: Text(done ? 'Tamamlandı' : button),
            ),
          ],
        ),
      ),
    );
  }
}
