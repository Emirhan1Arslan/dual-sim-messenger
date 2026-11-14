import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../platform.dart';
import '../store.dart';
import '../widgets.dart';
import 'conversation_screen.dart';

/// Yeni mesaj: numara, SIM seçimi ve mesaj alanı.
class ComposeScreen extends StatefulWidget {
  const ComposeScreen({
    super.key,
    this.preferredSubId,
    this.initialAddress = '',
    this.initialBody = '',
  });

  /// Ana ekranda seçili sekmenin SIM'i ("Tümü" sekmesinde null).
  final int? preferredSubId;
  final String initialAddress;
  final String initialBody;

  @override
  State<ComposeScreen> createState() => _ComposeScreenState();
}

class _ComposeScreenState extends State<ComposeScreen> {
  late final _address = TextEditingController(text: widget.initialAddress);
  late final _body = TextEditingController(text: widget.initialBody);

  /// Varsayılan: seçili sekmenin SIM'i, yoksa sistemin varsayılan SMS SIM'i.
  late int? _subId = store.pickSendSim(widget.preferredSubId);
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    store.addListener(_onStoreChanged);
    _address.addListener(_refresh);
    _body.addListener(_refresh);
  }

  @override
  void dispose() {
    store.removeListener(_onStoreChanged);
    _address.dispose();
    _body.dispose();
    super.dispose();
  }

  void _refresh() => setState(() {});

  void _onStoreChanged() {
    if (!mounted) return;
    setState(() {
      if (_subId == null || store.simFor(_subId!) == null) {
        _subId = store.pickSendSim(widget.preferredSubId);
      }
    });
  }

  bool get _canSend =>
      !_sending && _address.text.trim().isNotEmpty && _body.text.trim().isNotEmpty;

  Future<void> _send() async {
    if (!_canSend) return;
    final address = _address.text.trim();
    setState(() => _sending = true);
    try {
      final sent = await Sms.send(
        address: address,
        body: _body.text.trim(),
        subId: _subId,
      );
      if (!mounted) return;
      Navigator.of(context).pushReplacement(MaterialPageRoute(
        builder: (_) => ConversationScreen(
          threadId: sent.threadId,
          subId: sent.subId,
          address: address,
        ),
      ));
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _sending = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Gönderilemedi: ${e.message ?? 'bilinmeyen hata'}')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sims = store.sims;
    final sendColor = _subId == null ? null : store.simFor(_subId!)?.color;

    return Scaffold(
      appBar: AppBar(title: const Text('Yeni mesaj')),
      body: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _address,
                autofocus: widget.initialAddress.isEmpty,
                keyboardType: TextInputType.phone,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(
                  labelText: 'Numara',
                  prefixIcon: Icon(Icons.person_outline),
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              Text('Gönderilecek SIM', style: theme.textTheme.labelLarge),
              const SizedBox(height: 4),
              if (sims.isEmpty)
                Text(
                  'Aktif SIM bulunamadı; sistemin varsayılan SIM\'i kullanılacak.',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                )
              else
                SimSelector(
                  sims: sims,
                  selected: _subId,
                  onSelected: (subId) => setState(() => _subId = subId),
                ),
              const SizedBox(height: 16),
              Expanded(
                child: TextField(
                  controller: _body,
                  autofocus: widget.initialAddress.isNotEmpty,
                  expands: true,
                  maxLines: null,
                  textAlignVertical: TextAlignVertical.top,
                  textCapitalization: TextCapitalization.sentences,
                  decoration: const InputDecoration(
                    labelText: 'Mesaj',
                    alignLabelWithHint: true,
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                style: sendColor == null
                    ? null
                    : FilledButton.styleFrom(
                        backgroundColor: sendColor,
                        foregroundColor: Colors.white,
                      ),
                onPressed: _canSend ? _send : null,
                icon: const Icon(Icons.send),
                label: Text(_sending ? 'Gönderiliyor…' : 'Gönder'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
