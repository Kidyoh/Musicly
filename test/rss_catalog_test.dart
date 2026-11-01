import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/services/rss_api.dart';
import 'package:musicly/services/rss_catalog.dart';

void main() {
  test('the built-in feed list is clean', () {
    expect(rssCatalog, isNotEmpty);
    expect(rssCatalog.map((s) => s.url).toSet().length, rssCatalog.length);
    for (final s in rssCatalog) {
      expect(s.url, startsWith('https://'));
      expect(RssApi.normalizeUrl(s.url), s.url);
      expect(s.title, isNotEmpty);
    }
  });
}
