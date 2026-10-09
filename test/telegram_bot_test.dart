import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/services/telegram_bot.dart';

void main() {
  test('channel references', () {
    expect(TelegramBot.normaliseChat('@mychannel'), '@mychannel');
    expect(TelegramBot.normaliseChat('mychannel'), '@mychannel');
    expect(TelegramBot.normaliseChat('https://t.me/mychannel'), '@mychannel');
    expect(TelegramBot.normaliseChat('t.me/s/mychannel/123'), '@mychannel');
    expect(TelegramBot.normaliseChat(' -1001234567890 '), -1001234567890);
  });

  Map audio({
    String? title,
    String? performer,
    String? name,
    int size = 5000000,
    bool asDocument = false,
    String mime = 'audio/mpeg',
  }) {
    final file = {
      'file_id': 'FID',
      'file_unique_id': 'UID',
      'duration': 200,
      'title': ?title,
      'performer': ?performer,
      'file_name': ?name,
      'mime_type': mime,
      'file_size': size,
      'thumbnail': {'file_id': 'TH'},
    };
    return {asDocument ? 'document' : 'audio': file};
  }

  test('reads tags, then "Artist - Title" file names', () {
    var t = TelegramBot.trackFrom(
      audio(title: 'Ye', performer: 'Burna Boy'),
      'Vault',
    )!;
    expect(
      (t.title, t.artist, t.source, t.uri, t.artworkUrl, t.album),
      ('Ye', 'Burna Boy', TrackSource.telegram, 'FID', 'tgthumb:TH', 'Vault'),
    );
    expect(t.duration, const Duration(seconds: 200));
    t = TelegramBot.trackFrom(
      audio(name: 'Asake - Lonely_At_The_Top.mp3', asDocument: true),
      'Vault',
    )!;
    expect((t.title, t.artist), ('Lonely At The Top', 'Asake'));
  });

  test('skips non-audio and files bots cannot download', () {
    expect(TelegramBot.trackFrom({'text': 'hi'}, 'V'), isNull);
    expect(
      TelegramBot.trackFrom(
        audio(asDocument: true, mime: 'application/pdf'),
        'V',
      ),
      isNull,
    );
    final big = audio(title: 'Long mix', size: 30 * 1024 * 1024);
    expect(TelegramBot.trackFrom(big, 'V'), isNull);
    expect(TelegramBot.isTooBig(big), isTrue);
  });

  test('finds channels the bot was made admin of', () {
    Map added(int id, String title, String status, {String type = 'channel'}) =>
        {
          'my_chat_member': {
            'chat': {'id': id, 'title': title, 'type': type},
            'new_chat_member': {'status': status},
          },
        };
    final found = TelegramBot.channelsFrom([
      added(-1001, 'Old', 'administrator'),
      added(-1002, 'Removed', 'administrator'),
      {'channel_post': {}},
      added(-1003, 'A group', 'administrator', type: 'supergroup'),
      added(-1002, 'Removed', 'left'),
      added(-1004, 'New', 'administrator'),
    ]);
    expect(found.map((c) => c.title), ['New', 'Old']);
    expect(found.first.id, -1004);
  });
}
