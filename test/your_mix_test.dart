import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/state/library_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

Track song(
  String id,
  String artist, {
  TrackSource source = TrackSource.telegram,
}) => Track(
  id: id,
  title: 'Song $id',
  artist: artist,
  source: source,
  uri: 'file-$id',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    '"Your mix" uses only your own songs and favours what you like',
    () async {
      final channel = [
        for (var i = 0; i < 12; i++)
          song('tg:$i', ['Burna Boy', 'Asake', 'Tems', 'Wizkid'][i % 4]),
      ];
      SharedPreferences.setMockInitialValues({
        'telegram': jsonEncode({
          'token': '1:x',
          'channelId': -100,
          'name': 'Vault',
          'tracks': channel.map((t) => t.toJson()).toList(),
        }),
        // An old Deezer preview from a previous version must be dropped.
        'favorites': jsonEncode([
          song('tg:3', 'Wizkid').toJson(),
          {'id': 'dz:1', 'title': 'Old', 'artist': 'X', 'source': 'deezer'},
        ]),
        'plays': jsonEncode({
          'tracks': {'tg:1': 9},
          'artists': {'asake': 9},
        }),
      });
      final lib = LibraryController();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(lib.favorites.map((t) => t.id), ['tg:3']);
      lib.buildForYou();
      expect(lib.forYou, isNotEmpty);
      expect(lib.forYou.every((t) => t.id.startsWith('tg:')), isTrue);
      expect(lib.forYou.map((t) => t.id).toSet().length, lib.forYou.length);
      for (var i = 1; i < lib.forYou.length; i++) {
        expect(
          lib.forYou[i].artist,
          isNot(lib.forYou[i - 1].artist),
          reason: 'same artist twice in a row',
        );
      }
      expect(lib.topArtists(1), ['Asake']);
      expect(lib.forYouReason, contains('your channel'));
      expect(lib.mostPlayed.first.id, 'tg:1');
      expect(lib.artists.length, 4);
    },
  );
}
