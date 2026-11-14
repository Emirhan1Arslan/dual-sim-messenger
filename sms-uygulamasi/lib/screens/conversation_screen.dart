import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../platform.dart';
import '../store.dart';
import '../widgets.dart';

/// Tek bir (thread_id, sub_id) konuşması.
class ConversationScreen extends StatefulWidget {
  const ConversationScreen({
    super.key,
    required this.threadId,
    required this.subId,
    required this.address,
  });

  final int threadId;
  final int subId;
  final String address;

  @override
  State<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends State<ConversationScreen> {
  final _input = TextEditingController();

  /// Görüntülenen konuşmanın SIM'i; başka SIM'den cevap yazılırsa ona geçer.
  late int _subId = widget.subId;

  /// Cevabın gideceği SIM (varsayılan: konuşmanın SIM'i).
  late int? _sendSubId = store.pickSendSim(widget.subId);

  List<Message>? _messages;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    store.addListener(_onStoreChanged);
    _input.addListener(() => setState(() {}));
    Sms.setActive(widget.threadId, _subId);
    _load();
  }

  @override
  void dispose() {
    store.removeListener(_onStoreChanged);
    Sms.setActive(null, null);
    _input.dispose();
    super.dispose();
  }

  void _onStoreChanged() {
    // SIM listesi sonradan yüklendiyse varsayılan gönderim SIM'ini belirle.
    _sendSubId ??= store.pickSendSim(_subId);
    _load();
  }

  Future<void> _load() async {
    final subId = _subId;
    try {
      final messages = await Sms.messages(widget.threadId, subId);
      if (!mounted || subId != _subId) return;
      setState(() => _messages = messages);
      // Açık konuşmaya gelen yeni mesajları okundu olarak işaretle.
      if (store.conversationFor(widget.threadId, subId)?.unread != 0) {
        await Sms.markRead(widget.threadId, subId);
      }
    } on PlatformException {
      // Sonraki değişiklik olayında yeniden denenir.
    }
  }

  Future<void> _send() async {
    final body = _input.text.trim();
    if (body.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      final sent = await Sms.send(
        address: widget.address,
        body: body,
        subId: _sendSubId,
      );
      _input.clear();
      if (sent.subId != _subId) {
        // Başka SIM'den gönderildi: mesaj o SIM'in konuşmasına yazıldı, oraya geç.
        _subId = sent.subId;
        await Sms.setActive(widget.threadId, _subId);
      }
      await _load();
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Gönderilemedi: ${e.message ?? 'bilinmeyen hata'}')),
        );
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _confirmDelete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Konuşma silinsin mi?'),
        content: const Text(
          'Bu konuşmadaki bütün mesajlar kalıcı olarak silinecek.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Vazgeç'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Sil'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await Sms.deleteConversation(widget.threadId, _subId);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final conversation = store.conversationFor(widget.threadId, _subId);
    final title = conversation?.title ?? widget.address;
    final sim = store.simFor(_subId);
    final messages = _messages;

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
            if (sim != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Row(
                  children: [
                    SimBadge(sim),
                    if (sim.carrier.isNotEmpty) ...[
                      const SizedBox(width: 6),
                      Text(sim.carrier, style: theme.textTheme.bodySmall),
                    ],
                  ],
                ),
              ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Konuşmayı sil',
            icon: const Icon(Icons.delete_outline),
            onPressed: _confirmDelete,
          ),
        ],
      ),
      // Klavye açılınca gövde küçülür (imePadding karşılığı), mesaj kutusu yukarı kayar.
      resizeToAvoidBottomInset: true,
      body: Column(
        children: [
          Expanded(
            child: messages == null
                ? const Center(child: CircularProgressIndicator())
                : messages.isEmpty
                    ? Center(
                        child: Text(
                          'Bu konuşmada mesaj yok',
                          style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      )
                    : ListView.builder(
                        reverse: true,
                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                        itemCount: messages.length,
                        itemBuilder: (context, i) {
                          final index = messages.length - 1 - i;
                          final message = messages[index];
                          final previous = index > 0 ? messages[index - 1] : null;
                          final newDay = previous == null ||
                              !DateUtils.isSameDay(previous.date, message.date);
                          return Column(
                            key: ValueKey(message.id),
                            children: [
                              if (newDay) _DayLabel(message.date),
                              _Bubble(message: message, simColor: sim?.color),
                            ],
                          );
                        },
                      ),
          ),
          _buildComposer(theme),
        ],
      ),
    );
  }

  Widget _buildComposer(ThemeData theme) {
    final sims = store.sims;
    final sendColor = _sendSubId == null ? null : store.simFor(_sendSubId!)?.color;
    final canSend = _input.text.trim().isNotEmpty && !_sending;

    return Material(
      color: theme.colorScheme.surfaceContainer,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (sims.length > 1 || (sims.isNotEmpty && store.simFor(_subId) == null))
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: SimSelector(
                    sims: sims,
                    selected: _sendSubId,
                    onSelected: (subId) => setState(() => _sendSubId = subId),
                  ),
                ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      minLines: 1,
                      maxLines: 5,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: InputDecoration(
                        hintText: 'Mesaj yazın',
                        filled: true,
                        fillColor: theme.colorScheme.surface,
                        isDense: true,
                        contentPadding:
                            const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(24),
                          borderSide: BorderSide.none,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 4),
                  IconButton.filled(
                    tooltip: 'Gönder',
                    style: sendColor == null
                        ? null
                        : IconButton.styleFrom(
                            backgroundColor: sendColor,
                            foregroundColor: Colors.white,
                          ),
                    onPressed: canSend ? _send : null,
                    icon: const Icon(Icons.send),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DayLabel extends StatelessWidget {
  const _DayLabel(this.date);

  final DateTime date;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Text(
        formatDay(date),
        style: theme.textTheme.labelSmall
            ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
      ),
    );
  }
}

/// Gelen mesaj solda, giden mesaj sağda.
class _Bubble extends StatelessWidget {
  const _Bubble({required this.message, required this.simColor});

  final Message message;
  final Color? simColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final incoming = message.incoming;

    final background = incoming
        ? scheme.surfaceContainerHighest
        : message.failed
            ? scheme.errorContainer
            : scheme.primaryContainer;
    final foreground = incoming
        ? scheme.onSurface
        : message.failed
            ? scheme.onErrorContainer
            : scheme.onPrimaryContainer;

    final String status;
    if (message.sending) {
      status = 'Gönderiliyor…';
    } else if (message.failed) {
      status = 'Gönderilemedi';
    } else {
      status = formatClock(message.date);
    }

    return Align(
      alignment: incoming ? Alignment.centerLeft : Alignment.centerRight,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.78,
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Column(
            crossAxisAlignment:
                incoming ? CrossAxisAlignment.start : CrossAxisAlignment.end,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                decoration: BoxDecoration(
                  color: background,
                  borderRadius: BorderRadius.only(
                    topLeft: const Radius.circular(18),
                    topRight: const Radius.circular(18),
                    bottomLeft: Radius.circular(incoming ? 4 : 18),
                    bottomRight: Radius.circular(incoming ? 18 : 4),
                  ),
                ),
                child: SelectableText(
                  message.body,
                  style: theme.textTheme.bodyLarge?.copyWith(color: foreground),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(6, 2, 6, 2),
                child: Text(
                  status,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: message.failed ? scheme.error : scheme.onSurfaceVariant,
                    fontWeight: message.failed ? FontWeight.w700 : null,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
