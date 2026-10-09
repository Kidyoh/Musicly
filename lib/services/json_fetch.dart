import 'dart:convert';

import 'package:http/http.dart' as http;

/// GETs a URL and decodes its JSON body.
Future<dynamic> fetchJson(Uri uri) async {
  final res = await http.get(uri).timeout(const Duration(seconds: 12));
  if (res.statusCode != 200) throw Exception('HTTP ${res.statusCode}');
  return jsonDecode(utf8.decode(res.bodyBytes));
}
