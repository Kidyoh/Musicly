import 'package:flutter/material.dart';

import '../models/track.dart';
import 'widgets.dart';

final Map<String, Future<Color?>> _cache = {};

/// The dominant tone of a song's cover, used for the Now Playing glow.
Future<Color?> artColor(Track t) {
  final key = t.artworkUrl ?? (t.mediaId == null ? null : 'ms:${t.mediaId}');
  if (key == null) return Future.value(null);
  return _cache.putIfAbsent(key, () async {
    try {
      final provider = await artworkProvider(t);
      if (provider == null) return null;
      final scheme = await ColorScheme.fromImageProvider(provider: provider)
          .timeout(const Duration(seconds: 6));
      return scheme.primary;
    } catch (_) {
      return null;
    }
  });
}
