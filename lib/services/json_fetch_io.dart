import 'dart:convert';

import 'package:http/http.dart' as http;

/// Fetches JSON. [jsonp] is ignored outside the browser.
Future<dynamic> fetchJson(Uri uri, {bool jsonp = false}) async {
  final res = await http.get(uri).timeout(const Duration(seconds: 12));
  if (res.statusCode != 200) throw Exception('HTTP ${res.statusCode}');
  return jsonDecode(utf8.decode(res.bodyBytes));
}
