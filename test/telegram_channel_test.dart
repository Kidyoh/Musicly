import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/telegram_channel.dart';
import 'package:musicly/models/track.dart';

Map<String, dynamic> song(String id) => Track(
  id: 'tg:$id',
  title: 'Song $id',
  artist: 'Artist',
  source: TrackSource.telegram,
  uri: 'FILE$id',
).toJson();

void main() {
  test('a 1.0 single channel becomes the first channel', () {
    final list = TelegramChannel.listFromSettings({
      'token': '1:x',
      'channelId': -100,
      'name': 'Vault',
      'latest': 42,
      'tracks': [song('a'), song('b')],
    });
    expect(list, hasLength(1));
    expect(list.first.id, -100);
    expect(list.first.name, 'Vault');
    expect(list.first.latestPostId, 42);
    expect(list.first.tracks.map((t) => t.id), ['tg:a', 'tg:b']);
  });

  test('several channels round-trip', () {
    final saved = [
      TelegramChannel(id: -1, name: 'One', latestPostId: 3),
      TelegramChannel(id: -2, name: 'Two'),
    ];
    final back = TelegramChannel.listFromSettings({
      'channels': saved.map((c) => c.toJson()).toList(),
    });
    expect(back.map((c) => c.name), ['One', 'Two']);
    expect(back.first.latestPostId, 3);
    expect(TelegramChannel.listFromSettings({}), isEmpty);
  });

  test('restoring a backup merges channels and songs', () {
    final current = TelegramChannel.listFromSettings({
      'channels': [
        {
          'id': -1,
          'name': 'One',
          'latest': 5,
          'tracks': [song('new')],
        },
      ],
    });
    final backup = TelegramChannel.listFromSettings({
      'channels': [
        {
          'id': -1,
          'name': 'One',
          'latest': 9,
          'tracks': [song('old'), song('new')],
        },
        {
          'id': -2,
          'name': 'Two',
          'tracks': [song('x')],
        },
      ],
    });
    final same = TelegramChannel.merge(current, backup, sameBot: true);
    expect(same.map((c) => c.id), [-1, -2]);
    expect(same.first.tracks.map((t) => t.id), ['tg:new', 'tg:old']);
    expect(same.first.latestPostId, 9);

    final other = TelegramChannel.merge(current, backup, sameBot: false);
    expect(other.map((c) => c.id), [-1]);
  });
}
