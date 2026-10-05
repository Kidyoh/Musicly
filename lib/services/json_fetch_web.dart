import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:http/http.dart' as http;
import 'package:web/web.dart' as web;

@JS('JSON.stringify')
external JSString _stringify(JSAny? value);

int _seq = 0;

/// Fetches JSON. With [jsonp], loads it through a <script> tag for APIs
/// (like Deezer's) that don't send CORS headers.
Future<dynamic> fetchJson(Uri uri, {bool jsonp = false}) async {
  if (!jsonp) {
    final res = await http.get(uri).timeout(const Duration(seconds: 12));
    if (res.statusCode != 200) throw Exception('HTTP ${res.statusCode}');
    return jsonDecode(utf8.decode(res.bodyBytes));
  }
  final name = '__musiclyCb${_seq++}';
  final done = Completer<dynamic>();
  final script = web.HTMLScriptElement();

  void cleanup() {
    script.remove();
    // Leave a no-op behind: a reply that arrives after a timeout must not throw.
    globalContext.setProperty(name.toJS, ((JSAny? _) {}).toJS);
  }

  globalContext.setProperty(
    name.toJS,
    ((JSAny? data) {
      if (!done.isCompleted) done.complete(jsonDecode(_stringify(data).toDart));
    }).toJS,
  );
  script.onerror = ((web.Event _) {
    if (!done.isCompleted) done.completeError(Exception('JSONP failed'));
  }).toJS;
  final q = Map<String, String>.of(uri.queryParameters)
    ..['output'] = 'jsonp'
    ..['callback'] = name;
  script.src = uri.replace(queryParameters: q).toString();
  web.document.head!.append(script);
  try {
    return await done.future.timeout(const Duration(seconds: 12));
  } finally {
    cleanup();
  }
}
