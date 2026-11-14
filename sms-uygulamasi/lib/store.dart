import 'dart:async';

import 'package:flutter/material.dart';

import 'platform.dart';

/// Uygulamanın tek durum deposu (ViewModel karşılığı).
class AppStore extends ChangeNotifier {
  /// null: henüz bilinmiyor (açılış).
  bool? isDefault;
  bool permissionsOk = false;
  Color? seedColor;

  List<SimInfo> sims = const [];
  int defaultSubId = -1;
  List<Conversation> conversations = const [];
  bool loaded = false;

  AppAction? _pendingAction;
  StreamSubscription<Map>? _subscription;
  bool _loading = false;
  bool _loadAgain = false;
  bool _askedOptionalPermissions = false;

  bool get known => isDefault != null;
  bool get setupDone => isDefault == true && permissionsOk;

  Future<void> init() async {
    _subscription = Sms.events.listen(_onEvent);
    try {
      seedColor = await Sms.seedColor();
    } catch (_) {}
    _pendingAction = await Sms.initialAction();
    await refreshStatus();
  }

  void _onEvent(Map event) {
    switch (event['event']) {
      case 'changed':
        reload();
      case 'action':
        _pendingAction = AppAction.fromMap(event);
        notifyListeners();
    }
  }

  /// Rol ve izin durumunu yeniden okur; kurulum tamamsa verileri yükler.
  Future<void> refreshStatus() async {
    final status = await Sms.status();
    isDefault = status.isDefault;
    permissionsOk = status.permissionsOk;
    notifyListeners();
    if (setupDone) {
      // Rol, SMS izinlerini kendiliğinden verebilir; o durumda kurulumdaki izin adımı
      // atlanır. Kalan isteğe bağlı izinleri (bildirim, rehber) açılışta bir kez iste.
      if (!_askedOptionalPermissions) {
        _askedOptionalPermissions = true;
        unawaited(Sms.requestPermissions().then((_) => reload()));
      }
      await Sms.observe();
      await reload();
    }
  }

  Future<void> reload() async {
    if (!setupDone) return;
    if (_loading) {
      _loadAgain = true;
      return;
    }
    _loading = true;
    try {
      do {
        _loadAgain = false;
        final results = await Future.wait([
          Sms.sims(),
          Sms.defaultSubId(),
          Sms.conversations(),
        ]);
        sims = results[0] as List<SimInfo>;
        defaultSubId = results[1] as int;
        conversations = results[2] as List<Conversation>;
        loaded = true;
        notifyListeners();
      } while (_loadAgain);
    } catch (_) {
      // İzin ya da rol geri alınmış olabilir; durum bir sonraki açılışta yenilenir.
    } finally {
      _loading = false;
    }
  }

  /// Bekleyen isteği (bildirim / sms: bağlantısı) bir kez teslim eder.
  AppAction? takeAction() {
    final action = _pendingAction;
    _pendingAction = null;
    return action;
  }

  SimInfo? simFor(int subId) {
    for (final sim in sims) {
      if (sim.subId == subId) return sim;
    }
    return null;
  }

  Conversation? conversationFor(int threadId, int subId) {
    for (final c in conversations) {
      if (c.threadId == threadId && c.subId == subId) return c;
    }
    return null;
  }

  int unreadFor(int? subId) {
    var total = 0;
    for (final c in conversations) {
      if (subId == null || c.subId == subId) total += c.unread;
    }
    return total;
  }

  /// Gönderim için varsayılan SIM: tercih edilen → sistem varsayılanı → ilk SIM.
  int? pickSendSim(int? preferred) {
    if (preferred != null && simFor(preferred) != null) return preferred;
    if (simFor(defaultSubId) != null) return defaultSubId;
    return sims.isEmpty ? null : sims.first.subId;
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}

final store = AppStore();
