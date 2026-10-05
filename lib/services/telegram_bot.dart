import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/track.dart';

class TelegramError implements Exception {
  TelegramError(this.code, this.description, {this.retryAfter});
  final int code;
  final String description;
  final int? retryAfter;
  @override
  String toString() => 'Telegram $code: $description';
}

/// Talks to the Telegram Bot API straight from the app; no server involved.
/// The token is typed in by the user and stays on their phone.
class TelegramBot {
  TelegramBot(this.token);
  final String token;

  /// Always api.telegram.org in real builds; tests can point it elsewhere
  /// with --dart-define=TELEGRAM_API=….
  static const host = String.fromEnvironment(
    'TELEGRAM_API',
    defaultValue: 'https://api.telegram.org',
  );

  /// Bots can download files up to 20 MB.
  static const maxFileSize = 20 * 1024 * 1024;

  final Map<String, (String, DateTime)> _files = {};

  Future<dynamic> call(
    String method, [
    Map<String, Object?> params = const {},
  ]) async {
    final res = await http
        .post(
          Uri.parse('$host/bot$token/$method'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({
            for (final e in params.entries)
              if (e.value != null) e.key: e.value,
          }),
        )
        .timeout(const Duration(seconds: 30));
    final body = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    if (body['ok'] == true) return body['result'];
    throw TelegramError(
      (body['error_code'] as num?)?.toInt() ?? res.statusCode,
      body['description'] as String? ?? 'Unknown error',
      retryAfter: ((body['parameters'] as Map?)?['retry_after'] as num?)
          ?.toInt(),
    );
  }

  Future<Map> getMe() async => await call('getMe') as Map;

  /// [channel] can be `@name`, a t.me link, or a numeric id like -100123….
  Future<Map> getChat(String channel) async =>
      await call('getChat', {'chat_id': normaliseChat(channel)}) as Map;

  static Object normaliseChat(String input) {
    var s = input.trim();
    s = s.replaceFirst(RegExp(r'^(https?://)?(t\.me|telegram\.me)/'), '');
    s = s.replaceFirst(RegExp(r'^s/'), '');
    s = s.split(RegExp(r'[/?]')).first;
    if (RegExp(r'^-?\d+$').hasMatch(s)) return int.parse(s);
    return s.startsWith('@') ? s : '@$s';
  }

  Future<List<Map>> getUpdates(int offset) async => ((await call('getUpdates', {
    'offset': offset,
    'timeout': 0,
    'limit': 100,
    'allowed_updates': ['channel_post', 'edited_channel_post', 'message'],
  })) as List).cast<Map>();

  /// Signed download link for a file; Telegram keeps them valid for at least an hour.
  Future<String> fileUrl(String fileId) async {
    final hit = _files[fileId];
    if (hit != null && hit.$2.isAfter(DateTime.now())) return hit.$1;
    final f = await call('getFile', {'file_id': fileId}) as Map;
    final path = f['file_path'] as String?;
    if (path == null) throw TelegramError(400, 'File not available');
    final url = '$host/file/bot$token/$path';
    _files[fileId] = (url, DateTime.now().add(const Duration(minutes: 50)));
    return url;
  }

  /// Song from a channel post or a forwarded message; null if it isn't audio
  /// or is too big for a bot to stream.
  static Track? trackFrom(Map m, String channelName) {
    final audio = (m['audio'] ?? m['document']) as Map?;
    if (audio == null) return null;
    final mime = (audio['mime_type'] as String? ?? '').toLowerCase();
    if (m['audio'] == null && !mime.startsWith('audio/')) return null;
    final size = (audio['file_size'] as num?)?.toInt() ?? 0;
    if (size > maxFileSize) return null;

    final fileName = (audio['file_name'] as String? ?? '').replaceAll('_', ' ');
    final base = fileName.contains('.')
        ? fileName.substring(0, fileName.lastIndexOf('.'))
        : fileName;
    var title = (audio['title'] as String? ?? '').trim();
    var artist = (audio['performer'] as String? ?? '').trim();
    if (title.isEmpty && base.contains(' - ')) {
      artist = base.substring(0, base.indexOf(' - ')).trim();
      title = base.substring(base.indexOf(' - ') + 3).trim();
    }
    if (title.isEmpty) title = base.trim().isNotEmpty ? base.trim() : 'Track';
    final thumb = (audio['thumbnail'] ?? audio['thumb']) as Map?;
    return Track(
      id: 'tg:${audio['file_unique_id']}',
      title: title,
      artist: artist.isEmpty ? 'Unknown artist' : artist,
      source: TrackSource.telegram,
      uri: audio['file_id'] as String,
      artworkUrl: thumb == null ? null : 'tgthumb:${thumb['file_id']}',
      duration: Duration(seconds: (audio['duration'] as num?)?.toInt() ?? 0),
      album: channelName,
      mime: mime.startsWith('audio/') ? mime : 'audio/mpeg',
    );
  }

  static bool isTooBig(Map m) {
    final a = (m['audio'] ?? m['document']) as Map?;
    return a != null && ((a['file_size'] as num?)?.toInt() ?? 0) > maxFileSize;
  }
}

/// Lets widgets turn `tgthumb:<file_id>` cover references into real links.
abstract final class TelegramFiles {
  static TelegramBot? bot;

  static bool isThumb(String? url) => url != null && url.startsWith('tgthumb:');

  static final Map<String, String> _thumbs = {};

  /// Already-known link, so covers draw without waiting.
  static String? cachedThumb(String ref) => _thumbs[ref];

  static Future<String?> resolveThumb(String ref) async {
    final b = bot;
    if (b == null) return null;
    try {
      return _thumbs[ref] = await b.fileUrl(ref.substring(8));
    } catch (_) {
      return null;
    }
  }
}
