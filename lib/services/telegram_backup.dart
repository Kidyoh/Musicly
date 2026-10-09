import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/telegram_channel.dart';
import 'telegram_bot.dart';

/// Keeps a copy of the whole library (likes, playlists, history, settings,
/// the Telegram song list) as one pinned file in the user's private chat
/// with their bot, so a reinstall can bring everything back. No server.
class TelegramBackup {
  TelegramBackup(this.bot, this.chatId);
  final TelegramBot bot;
  final int chatId;

  static const fileName = 'musicly-backup.json';
  static const _metaKeys = {'backup_msg', 'backup_at'};

  /// Everything in the app's preferences, minus the bot token (the user types
  /// it in again when reconnecting) and backup bookkeeping.
  static Future<Map<String, Object?>> snapshot() async {
    final p = await SharedPreferences.getInstance();
    final data = <String, Object?>{};
    for (final k in p.getKeys()) {
      if (_metaKeys.contains(k)) continue;
      var v = p.get(k);
      if (k == 'telegram' && v is String) {
        final j = jsonDecode(v) as Map<String, dynamic>..remove('token');
        v = jsonEncode(j);
      }
      data[k] = v;
    }
    return {
      'app': 'musicly',
      'version': 1,
      'savedAt': DateTime.now().toIso8601String(),
      'prefs': data,
    };
  }

  /// Uploads the snapshot. Edits the existing pinned backup when there is one,
  /// so the chat keeps a single file. Returns the message id.
  Future<int> upload(Map<String, Object?> snap, {int? existing}) async {
    final bytes = utf8.encode(jsonEncode(snap));
    final caption =
        '🎵 Musicly backup · ${DateTime.now().toLocal().toString().substring(0, 16)}\n'
        'Keep this pinned. It restores your library after a reinstall.';
    if (existing != null) {
      try {
        await _multipart(
          'editMessageMedia',
          {
            'chat_id': '$chatId',
            'message_id': '$existing',
            'media': jsonEncode({
              'type': 'document',
              'media': 'attach://backup',
              'caption': caption,
            }),
          },
          'backup',
          bytes,
        );
        return existing;
      } on TelegramError catch (e) {
        // Unchanged content is fine; a deleted message means start over.
        if (e.description.contains('not modified')) return existing;
      }
    }
    final sent = await _multipart(
      'sendDocument',
      {
        'chat_id': '$chatId',
        'caption': caption,
        'disable_notification': 'true',
      },
      'document',
      bytes,
    ) as Map;
    final id = (sent['message_id'] as num).toInt();
    try {
      await bot.call('pinChatMessage', {
        'chat_id': chatId,
        'message_id': id,
        'disable_notification': true,
      });
    } catch (_) {}
    return id;
  }

  /// The pinned backup in the bot chat, if any.
  Future<Map<String, dynamic>?> latest() async {
    final chat = await bot.call('getChat', {'chat_id': chatId}) as Map;
    final pinned = chat['pinned_message'] as Map?;
    final doc = pinned?['document'] as Map?;
    if (doc == null || doc['file_name'] != fileName) return null;
    final url = await bot.fileUrl(doc['file_id'] as String);
    final res = await http
        .get(Uri.parse(url))
        .timeout(const Duration(seconds: 30));
    if (res.statusCode != 200) return null;
    final j = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    if (j['app'] != 'musicly') return null;
    return {...j, '_messageId': pinned!['message_id']};
  }

  /// Writes a backup into preferences. The current bot token wins; channels
  /// and their song lists are merged with the ones in the backup.
  static Future<void> apply(Map<String, dynamic> backup) async {
    final p = await SharedPreferences.getInstance();
    final prefs = (backup['prefs'] as Map).cast<String, dynamic>();
    for (final e in prefs.entries) {
      final k = e.key, v = e.value;
      // Saved-song links are rebuilt from the files on the phone instead.
      if (k == 'telegram' || k == 'downloads') continue;
      if (v is bool) {
        await p.setBool(k, v);
      } else if (v is int) {
        await p.setInt(k, v);
      } else if (v is double) {
        await p.setDouble(k, v);
      } else if (v is String) {
        await p.setString(k, v);
      } else if (v is List) {
        await p.setStringList(k, v.cast<String>());
      }
    }
    final current = p.getString('telegram');
    final saved = prefs['telegram'] as String?;
    if (current != null && saved != null) {
      final cur = jsonDecode(current) as Map<String, dynamic>;
      final old = jsonDecode(saved) as Map<String, dynamic>;
      final channels = TelegramChannel.merge(
        TelegramChannel.listFromSettings(cur),
        TelegramChannel.listFromSettings(old),
        sameBot: old['token'] == cur['token'],
      );
      await p.setString(
        'telegram',
        jsonEncode({
          ...cur,
          'userChat': cur['userChat'] ?? old['userChat'],
          'channels': channels.map((c) => c.toJson()).toList(),
          'forwarded': _union(cur['forwarded'], old['forwarded']),
        }),
      );
    }
  }

  static List<Object?> _union(Object? a, Object? b) {
    final out = [...(a as List?) ?? const []];
    final ids = {for (final t in out) (t as Map)['id']};
    for (final t in (b as List?) ?? const []) {
      if (ids.add((t as Map)['id'])) out.add(t);
    }
    return out;
  }

  Future<dynamic> _multipart(
    String method,
    Map<String, String> fields,
    String fileField,
    List<int> bytes,
  ) async {
    final req =
        http.MultipartRequest(
            'POST',
            Uri.parse('${TelegramBot.host}/bot${bot.token}/$method'),
          )
          ..fields.addAll(fields)
          ..files.add(
            http.MultipartFile.fromBytes(fileField, bytes, filename: fileName),
          );
    final res = await http.Response.fromStream(await req.send())
        .timeout(const Duration(seconds: 60));
    final body = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    if (body['ok'] == true) return body['result'];
    throw TelegramError(
      (body['error_code'] as num?)?.toInt() ?? res.statusCode,
      body['description'] as String? ?? 'Unknown error',
    );
  }
}
