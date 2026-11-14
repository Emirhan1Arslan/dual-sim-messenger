import 'package:flutter/services.dart';

/// Telephony.Sms.MESSAGE_TYPE_* değerleri.
abstract final class MessageType {
  static const inbox = 1;
  static const sent = 2;
  static const outbox = 4;
  static const failed = 5;
  static const queued = 6;
}

class SimInfo {
  const SimInfo({required this.subId, required this.slot, required this.carrier});

  final int subId;
  final int slot;
  final String carrier;

  static const sim1Color = Color(0xFF1E88E5);
  static const sim2Color = Color(0xFF43A047);

  /// "SIM 1" / "SIM 2"
  String get label => 'SIM ${slot + 1}';

  /// "SIM 1 · Operatör"
  String get fullLabel => carrier.isEmpty ? label : '$label · $carrier';

  /// Her SIM'in sabit rengi: SIM 1 mavi, SIM 2 yeşil.
  Color get color => switch (slot) {
        0 => sim1Color,
        1 => sim2Color,
        _ => const Color(0xFF757575),
      };

  factory SimInfo.fromMap(Map map) => SimInfo(
        subId: (map['subId'] as num).toInt(),
        slot: (map['slot'] as num).toInt(),
        carrier: map['carrier'] as String? ?? '',
      );
}

/// (thread_id, sub_id) çiftiyle tanımlanan konuşma.
class Conversation {
  const Conversation({
    required this.threadId,
    required this.subId,
    required this.address,
    required this.name,
    required this.snippet,
    required this.date,
    required this.unread,
    required this.lastType,
  });

  final int threadId;
  final int subId;
  final String address;
  final String? name;
  final String snippet;
  final DateTime date;
  final int unread;
  final int lastType;

  String get title => (name == null || name!.isEmpty) ? address : name!;
  bool get lastIsOutgoing => lastType != MessageType.inbox;

  factory Conversation.fromMap(Map map) => Conversation(
        threadId: (map['threadId'] as num).toInt(),
        subId: (map['subId'] as num).toInt(),
        address: map['address'] as String? ?? '',
        name: map['name'] as String?,
        snippet: map['snippet'] as String? ?? '',
        date: DateTime.fromMillisecondsSinceEpoch((map['date'] as num).toInt()),
        unread: (map['unread'] as num).toInt(),
        lastType: (map['type'] as num).toInt(),
      );
}

class Message {
  const Message({
    required this.id,
    required this.body,
    required this.date,
    required this.type,
  });

  final int id;
  final String body;
  final DateTime date;
  final int type;

  bool get incoming => type == MessageType.inbox;
  bool get sending => type == MessageType.outbox || type == MessageType.queued;
  bool get failed => type == MessageType.failed;

  factory Message.fromMap(Map map) => Message(
        id: (map['id'] as num).toInt(),
        body: map['body'] as String? ?? '',
        date: DateTime.fromMillisecondsSinceEpoch((map['date'] as num).toInt()),
        type: (map['type'] as num).toInt(),
      );
}

/// Bildirime dokunma ya da sms:/smsto: bağlantısıyla gelen istek.
class AppAction {
  const AppAction.conversation({
    required this.threadId,
    required this.subId,
    required this.address,
  })  : isCompose = false,
        body = '';

  const AppAction.compose({required this.address, required this.body})
      : isCompose = true,
        threadId = -1,
        subId = -1;

  final bool isCompose;
  final int threadId;
  final int subId;
  final String address;
  final String body;

  static AppAction? fromMap(Map? map) {
    if (map == null) return null;
    return switch (map['type']) {
      'conversation' => AppAction.conversation(
          threadId: (map['threadId'] as num).toInt(),
          subId: (map['subId'] as num).toInt(),
          address: map['address'] as String? ?? '',
        ),
      'compose' => AppAction.compose(
          address: map['address'] as String? ?? '',
          body: map['body'] as String? ?? '',
        ),
      _ => null,
    };
  }
}

/// Android tarafıyla (MainActivity.kt) konuşan platform kanalı.
abstract final class Sms {
  static const _methods = MethodChannel('ciftsim/sms');
  static const _events = EventChannel('ciftsim/events');

  static Stream<Map> get events =>
      _events.receiveBroadcastStream().map((event) => event as Map);

  static Future<({bool isDefault, bool permissionsOk})> status() async {
    final map = (await _methods.invokeMapMethod<String, dynamic>('getStatus'))!;
    return (
      isDefault: map['isDefault'] as bool,
      permissionsOk: map['permissionsOk'] as bool,
    );
  }

  static Future<bool> requestRole() async =>
      await _methods.invokeMethod<bool>('requestRole') ?? false;

  static Future<bool> requestPermissions() async =>
      await _methods.invokeMethod<bool>('requestPermissions') ?? false;

  static Future<void> openAppSettings() => _methods.invokeMethod('openAppSettings');

  static Future<void> observe() => _methods.invokeMethod('observe');

  static Future<Color?> seedColor() async {
    final value = await _methods.invokeMethod<int>('getSeedColor');
    return value == null ? null : Color(value & 0xFFFFFFFF);
  }

  static Future<AppAction?> initialAction() async =>
      AppAction.fromMap(await _methods.invokeMethod<Map>('getInitialAction'));

  static Future<void> setActive(int? threadId, int? subId) => _methods
      .invokeMethod('setActive', {'threadId': threadId ?? -1, 'subId': subId ?? -1});

  static Future<List<SimInfo>> sims() async {
    final list = await _methods.invokeListMethod<Map>('getSims') ?? const [];
    return list.map(SimInfo.fromMap).toList();
  }

  static Future<int> defaultSubId() async =>
      await _methods.invokeMethod<int>('getDefaultSubId') ?? -1;

  static Future<List<Conversation>> conversations() async {
    final list = await _methods.invokeListMethod<Map>('getConversations') ?? const [];
    return list.map(Conversation.fromMap).toList();
  }

  static Future<List<Message>> messages(int threadId, int subId) async {
    final list = await _methods.invokeListMethod<Map>(
            'getMessages', {'threadId': threadId, 'subId': subId}) ??
        const [];
    return list.map(Message.fromMap).toList();
  }

  /// Dönen değer: mesajın yazıldığı konuşma (threadId, kullanılan subId).
  static Future<({int threadId, int subId})> send({
    required String address,
    required String body,
    required int? subId,
  }) async {
    final map = (await _methods.invokeMapMethod<String, dynamic>(
        'sendSms', {'address': address, 'body': body, 'subId': subId ?? -1}))!;
    return (
      threadId: (map['threadId'] as num).toInt(),
      subId: (map['subId'] as num).toInt(),
    );
  }

  static Future<void> markRead(int threadId, int subId) =>
      _methods.invokeMethod('markRead', {'threadId': threadId, 'subId': subId});

  static Future<void> deleteConversation(int threadId, int subId) => _methods
      .invokeMethod('deleteConversation', {'threadId': threadId, 'subId': subId});
}
