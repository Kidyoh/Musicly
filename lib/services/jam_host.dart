import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'jam_protocol.dart';
import 'jam_session.dart';

/// The host side of a Jam in the same room: a small web server on this
/// phone that friends' Musicly apps connect to, plus a beacon so they find
/// it on the Wi-Fi.
///
/// It knows nothing about playback; the callbacks connect it to the player.
class JamHost implements JamHosting {
  JamHost({
    required this.jamName,
    required this.hostName,
    required this.uploadsDir,
    required this.app,
  });

  final String jamName;
  final String hostName;
  final Directory uploadsDir;
  final JamHostCallbacks app;

  bool _guestsControl = false;
  @override
  set guestsControl(bool on) => _guestsControl = on;

  void Function()? _onPeopleChanged;
  @override
  set onPeopleChanged(void Function()? f) => _onPeopleChanged = f;

  HttpServer? _server;
  RawDatagramSocket? _beaconSocket;
  Timer? _beacon;
  final Map<String, JamPerson> _guests = {};
  final Map<String, WebSocket> _sockets = {};

  @override
  List<JamPerson> get guests => _guests.values.toList();
  final Map<String, String> _pendingUploads = {}; // upload id -> audio path
  final Map<String, String> _pendingArt = {}; // upload id -> art path
  int _seq = 0;

  int get port => _server?.port ?? 0;

  /// The phone's address on the Wi-Fi or hotspot, for the join code.
  static Future<String?> localAddress() async {
    final all = await NetworkInterface.list(type: InternetAddressType.IPv4);
    int rank(NetworkInterface i) {
      final n = i.name.toLowerCase();
      if (n.startsWith('wlan') || n.startsWith('swlan')) return 0;
      if (n.startsWith('ap') || n.contains('softap')) return 1;
      if (n.startsWith('eth') || n.startsWith('en')) return 2;
      // Mobile data last: friends can't reach the phone through it.
      if (n.startsWith('rmnet') || n.startsWith('ccmni')) return 9;
      return 5;
    }

    all.sort((a, b) => rank(a).compareTo(rank(b)));
    for (final i in all) {
      for (final a in i.addresses) {
        if (!a.isLoopback && !a.isLinkLocal) return a.address;
      }
    }
    return null;
  }

  Future<void> start({int port = 0, bool beacon = true}) async {
    await uploadsDir.create(recursive: true);
    _server = await HttpServer.bind(InternetAddress.anyIPv4, port);
    _server!.listen(_handle, onError: (_) {});
    if (beacon) await _startBeacon();
  }

  @override
  Future<void> stop({bool deleteUploads = true}) async {
    _beacon?.cancel();
    _beaconSocket?.close();
    for (final g in guests) {
      _send(g, jsonEncode({'t': 'bye', 'reason': 'ended'}));
      await _sockets[g.id]?.close();
    }
    _guests.clear();
    _sockets.clear();
    await _server?.close(force: true);
    _server = null;
    if (!deleteUploads) return;
    try {
      await uploadsDir.delete(recursive: true);
    } catch (_) {}
  }

  /// Sends to one guest; a guest who just left is skipped quietly.
  void _send(JamPerson g, String msg) {
    try {
      _sockets[g.id]?.add(msg);
    } catch (_) {}
  }

  /// Sends the current Jam to every guest.
  @override
  void pushState() {
    if (_guests.isEmpty) return;
    final msg = jsonEncode(_stateWithPeople().toJson());
    for (final g in guests) {
      _send(g, msg);
    }
  }

  @override
  void remove(String guestId) {
    final g = _guests.remove(guestId);
    if (g == null) return;
    _send(g, jsonEncode({'t': 'bye', 'reason': 'removed'}));
    _sockets.remove(guestId)?.close();
    _onPeopleChanged?.call();
    pushState();
  }

  JamState _stateWithPeople() =>
      app.state().copyWith(people: [hostName, ...guests.map((g) => g.name)]);

  // ---- Beacon ------------------------------------------------------------

  Future<void> _startBeacon() async {
    try {
      final s = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
      s.broadcastEnabled = true;
      _beaconSocket = s;
      _beacon = Timer.periodic(const Duration(seconds: 2), (_) => _ping());
      _ping();
    } catch (_) {}
  }

  Future<void> _ping() async {
    final s = _beaconSocket;
    if (s == null) return;
    final data = utf8.encode(
      jsonEncode({
        'tag': beaconTag,
        'v': 1,
        'name': jamName,
        'host': hostName,
        'port': port,
        'people': _guests.length + 1,
      }),
    );
    final targets = {InternetAddress('255.255.255.255')};
    try {
      // Also the subnet broadcast of each network, for hotspots.
      for (final i in await NetworkInterface.list(
        type: InternetAddressType.IPv4,
      )) {
        for (final a in i.addresses) {
          if (a.isLoopback) continue;
          final p = a.address.split('.');
          targets.add(InternetAddress('${p[0]}.${p[1]}.${p[2]}.255'));
        }
      }
    } catch (_) {}
    for (final t in targets) {
      try {
        s.send(data, t, beaconPort);
      } catch (_) {}
    }
  }

  // ---- Requests ------------------------------------------------------------

  Future<void> _handle(HttpRequest req) async {
    final path = req.uri.pathSegments;
    try {
      if (path.length == 1 && path[0] == 'ws') {
        await _join(req);
      } else if (path.length == 2 && path[0] == 'art') {
        await _art(req, path[1]);
      } else if (path.length == 1 && path[0] == 'upload') {
        await _receiveUpload(req);
      } else if (path.length == 1 && path[0] == 'hello') {
        _json(req, {'tag': beaconTag, 'name': jamName, 'host': hostName});
      } else {
        req.response.statusCode = 404;
        await req.response.close();
      }
    } catch (_) {
      try {
        req.response.statusCode = 500;
        await req.response.close();
      } catch (_) {}
    }
  }

  void _json(HttpRequest req, Object body) {
    req.response.headers.contentType = ContentType.json;
    req.response.write(jsonEncode(body));
    req.response.close();
  }

  Future<void> _join(HttpRequest req) async {
    final name = (req.uri.queryParameters['name'] ?? 'Guest').trim();
    final ws = await WebSocketTransformer.upgrade(req);
    ws.pingInterval = const Duration(seconds: 10);
    final g = JamPerson(
      '${DateTime.now().microsecondsSinceEpoch}-${_seq++}',
      name.isEmpty ? 'Guest' : name.substring(0, name.length.clamp(0, 30)),
    );
    _guests[g.id] = g;
    _sockets[g.id] = ws;
    ws.add(
      jsonEncode({
        't': 'welcome',
        'name': jamName,
        'host': hostName,
        'you': g.id,
      }),
    );
    _onPeopleChanged?.call();
    pushState();
    ws.listen(
      (raw) => _message(g, raw),
      onDone: () {
        _sockets.remove(g.id);
        if (_guests.remove(g.id) != null) {
          _onPeopleChanged?.call();
          pushState();
        }
      },
      onError: (_) {},
      cancelOnError: true,
    );
  }

  Future<void> _message(JamPerson g, Object? raw) async {
    if (raw is! String) return;
    final Map m;
    try {
      m = jsonDecode(raw) as Map;
    } catch (_) {
      return;
    }
    switch (m['t']) {
      case 'ping':
        _send(
          g,
          jsonEncode({
            't': 'pong',
            'c': m['c'],
            'h': DateTime.now().millisecondsSinceEpoch,
          }),
        );
      case 'search':
        _send(
          g,
          jsonEncode({
            't': 'results',
            'rid': m['rid'],
            'items': app
                .search('${m['q'] ?? ''}')
                .take(80)
                .map((t) => t.toJson())
                .toList(),
          }),
        );
      case 'add':
        await app.onAdd('${m['ref']}', g, m['next'] == true);
      case 'addUpload':
        final id = '${m['upload']}';
        final audio = _pendingUploads.remove(id);
        if (audio == null) return;
        await app.onUpload(
          JamUpload(
            source: audio,
            art: _pendingArt.remove(id),
            title: (m['title'] as String?) ?? 'Unknown',
            artist: (m['artist'] as String?) ?? 'Unknown artist',
            durationMs: (m['dur'] as num?)?.toInt(),
          ),
          g,
          m['next'] == true,
        );
      case 'ctl':
        if (_guestsControl) {
          app.onControl('${m['a']}', (m['ms'] as num?)?.toInt(), g);
        }
    }
  }

  Future<void> _art(HttpRequest req, String id) async {
    final bytes = await app.art(Uri.decodeComponent(id));
    if (bytes == null) {
      req.response.statusCode = 404;
    } else {
      req.response.headers
        ..contentType = ContentType('image', 'jpeg')
        ..set('Cache-Control', 'max-age=3600');
      req.response.add(bytes);
    }
    await req.response.close();
  }

  /// `POST /upload?g=guest&id=upload&kind=audio|art&ext=mp3`
  Future<void> _receiveUpload(HttpRequest req) async {
    final q = req.uri.queryParameters;
    final id = (q['id'] ?? '').replaceAll(RegExp(r'[^\w-]'), '');
    final kind = q['kind'] == 'art' ? 'art' : 'audio';
    final ext = (q['ext'] ?? 'mp3').replaceAll(RegExp(r'[^\w]'), '');
    if (req.method != 'POST' || !_guests.containsKey(q['g']) || id.isEmpty) {
      req.response.statusCode = 403;
      await req.response.close();
      return;
    }
    final limit = kind == 'art' ? 2 << 20 : 80 << 20;
    final file = File(
      '${uploadsDir.path}/$id.${kind == 'art'
          ? 'jpg'
          : ext.isEmpty
          ? 'mp3'
          : ext}',
    );
    final sink = file.openWrite();
    var size = 0;
    var tooBig = false;
    await for (final chunk in req) {
      size += chunk.length;
      if (size > limit) {
        tooBig = true;
        break;
      }
      sink.add(chunk);
    }
    await sink.close();
    if (tooBig) {
      await file.delete();
      req.response.statusCode = 413;
      await req.response.close();
      return;
    }
    (kind == 'art' ? _pendingArt : _pendingUploads)[id] = file.path;
    _json(req, {'ok': true});
  }
}
