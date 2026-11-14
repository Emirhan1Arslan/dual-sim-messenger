import 'package:flutter/material.dart';

import '../platform.dart';
import '../store.dart';
import '../widgets.dart';
import 'compose_screen.dart';
import 'conversation_screen.dart';

/// Konuşma listesi: "Tümü" + her SIM için bir sekme.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  void initState() {
    super.initState();
    store.addListener(_onStore);
    WidgetsBinding.instance.addPostFrameCallback((_) => _handleAction());
  }

  @override
  void dispose() {
    store.removeListener(_onStore);
    super.dispose();
  }

  void _onStore() {
    if (!mounted) return;
    setState(() {});
    _handleAction();
  }

  /// Bildirime dokunma ya da sms: bağlantısı ile gelen isteği açar.
  void _handleAction() {
    if (!mounted) return;
    final action = store.takeAction();
    if (action == null) return;
    final navigator = Navigator.of(context);
    navigator.popUntil((route) => route.isFirst);
    navigator.push(MaterialPageRoute(
      builder: (_) => action.isCompose
          ? ComposeScreen(initialAddress: action.address, initialBody: action.body)
          : ConversationScreen(
              threadId: action.threadId,
              subId: action.subId,
              address: action.address,
            ),
    ));
  }

  void _open(Conversation c) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => ConversationScreen(
        threadId: c.threadId,
        subId: c.subId,
        address: c.address,
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final sims = store.sims;
    return DefaultTabController(
      // SIM takılıp çıkarıldığında sekmeler yeniden kurulsun.
      key: ValueKey(sims.map((s) => s.subId).join(',')),
      length: sims.length + 1,
      child: Builder(
        builder: (context) => Scaffold(
          appBar: AppBar(
            title: const Text('Çift SIM Mesajlar'),
            bottom: TabBar(
              isScrollable: true,
              tabAlignment: TabAlignment.start,
              tabs: [
                _SimTab(label: 'Tümü', unread: store.unreadFor(null)),
                for (final sim in sims)
                  _SimTab(
                    label: sim.fullLabel,
                    color: sim.color,
                    unread: store.unreadFor(sim.subId),
                  ),
              ],
            ),
          ),
          body: TabBarView(
            children: [
              _ConversationList(subId: null, onOpen: _open),
              for (final sim in sims)
                _ConversationList(subId: sim.subId, onOpen: _open),
            ],
          ),
          floatingActionButton: FloatingActionButton.extended(
            icon: const Icon(Icons.edit_outlined),
            label: const Text('Yeni mesaj'),
            onPressed: () {
              // Varsayılan SIM: seçili sekmenin SIM'i.
              final index = DefaultTabController.of(context).index;
              Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => ComposeScreen(
                  preferredSubId: index == 0 ? null : sims[index - 1].subId,
                ),
              ));
            },
          ),
        ),
      ),
    );
  }
}

class _SimTab extends StatelessWidget {
  const _SimTab({required this.label, required this.unread, this.color});

  final String label;
  final int unread;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Tab(
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (color != null) ...[
            CircleAvatar(radius: 5, backgroundColor: color),
            const SizedBox(width: 6),
          ],
          Text(label),
          if (unread > 0) ...[
            const SizedBox(width: 6),
            Badge(
              label: Text(unread > 99 ? '99+' : '$unread'),
              backgroundColor: color,
            ),
          ],
        ],
      ),
    );
  }
}

class _ConversationList extends StatelessWidget {
  const _ConversationList({required this.subId, required this.onOpen});

  final int? subId;
  final ValueChanged<Conversation> onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final items = subId == null
        ? store.conversations
        : store.conversations.where((c) => c.subId == subId).toList();

    if (!store.loaded) {
      return const Center(child: CircularProgressIndicator());
    }
    if (items.isEmpty) {
      return Center(
        child: Text(
          'Henüz mesaj yok',
          style: theme.textTheme.bodyLarge
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.only(bottom: 96),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final c = items[i];
        final sim = store.simFor(c.subId);
        final unread = c.unread > 0;
        final weight = unread ? FontWeight.w700 : FontWeight.w400;
        final initial = c.name == null || c.name!.isEmpty
            ? null
            : c.name!.characters.first.toUpperCase();
        return ListTile(
          key: ValueKey('${c.threadId}/${c.subId}'),
          onTap: () => onOpen(c),
          leading: CircleAvatar(
            child: initial == null ? const Icon(Icons.person) : Text(initial),
          ),
          title: Row(
            children: [
              Expanded(
                child: Text(
                  c.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontWeight: unread ? FontWeight.w700 : FontWeight.w500),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                formatListTime(c.date),
                style: theme.textTheme.bodySmall?.copyWith(
                  fontWeight: weight,
                  color: unread
                      ? theme.colorScheme.onSurface
                      : theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          subtitle: Row(
            children: [
              Expanded(
                child: Text(
                  c.lastIsOutgoing ? 'Siz: ${c.snippet}' : c.snippet,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontWeight: weight,
                    color: unread ? theme.colorScheme.onSurface : null,
                  ),
                ),
              ),
              if (sim != null) ...[
                const SizedBox(width: 8),
                SimBadge(sim),
              ],
              if (unread) ...[
                const SizedBox(width: 6),
                Badge(
                  label: Text(c.unread > 99 ? '99+' : '${c.unread}'),
                  backgroundColor: sim?.color,
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}
