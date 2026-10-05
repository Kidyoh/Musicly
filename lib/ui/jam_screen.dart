import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../models/track.dart';
import '../services/jam_protocol.dart';
import '../state/jam_controller.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Listen together: start a Jam (in this room or online), or join one.
class JamScreen extends StatelessWidget {
  const JamScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final jam = context.watch<JamController>();
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
      ),
      body: switch (jam.role) {
        JamRole.none => const _Start(),
        JamRole.host => const _Hosting(),
        JamRole.guest => const _Joined(),
      },
    );
  }
}

const _red = Color(0xFFE5484D);

Widget _title(String text) => Text(
  text,
  style: const TextStyle(
    fontSize: 28,
    fontWeight: FontWeight.w700,
    letterSpacing: -0.6,
  ),
);

Widget _card(BuildContext context, Widget child) => Container(
  padding: const EdgeInsets.all(16),
  decoration: BoxDecoration(
    color: Palette.of(context).card,
    borderRadius: BorderRadius.circular(18),
  ),
  child: child,
);

/// "Abel added Blinding Lights", shown under the header.
Widget _eventPill(BuildContext context, String? event) {
  if (event == null) return const SizedBox.shrink();
  final p = Palette.of(context);
  return Padding(
    padding: const EdgeInsets.only(top: 14),
    child: Row(
      children: [
        Icon(AppIcons.sparkle, size: 16, color: p.sub),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            event,
            style: TextStyle(color: p.sub),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    ),
  );
}

Widget _people(BuildContext context, List<String> names) => Wrap(
  spacing: 8,
  runSpacing: 8,
  children: [
    for (var i = 0; i < names.length; i++)
      Chip(
        avatar: i == 0 ? const Icon(AppIcons.crown, size: 16) : null,
        label: Text(names[i]),
      ),
  ],
);

Future<String?> _askName(BuildContext context, String current) {
  final ctl = TextEditingController(text: current);
  return showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Your name in the Jam'),
      content: TextField(
        controller: ctl,
        autofocus: true,
        textCapitalization: TextCapitalization.words,
        decoration: const InputDecoration(hintText: 'e.g. Abel'),
        onSubmitted: (v) => Navigator.pop(ctx, v),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, ctl.text),
          child: const Text('Save'),
        ),
      ],
    ),
  );
}

/// Makes sure friends will see a name; returns false if the user backed out.
Future<bool> _ensureName(BuildContext context) async {
  final jam = context.read<JamController>();
  if (jam.hasName) return true;
  final name = await _askName(context, '');
  if (name == null || name.trim().isEmpty) return false;
  await jam.setName(name);
  return true;
}

// ---- Not in a Jam ----------------------------------------------------------

class _Start extends StatefulWidget {
  const _Start();
  @override
  State<_Start> createState() => _StartState();
}

class _StartState extends State<_Start> {
  final _code = TextEditingController();
  bool _busy = false;
  late final JamController _jam;

  @override
  void initState() {
    super.initState();
    _jam = context.read<JamController>();
    _jam.startLooking();
    final err = _jam.error;
    if (err != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) toast(context, err);
        _jam.error = null;
      });
    }
  }

  @override
  void dispose() {
    _jam.stopLooking();
    _code.dispose();
    super.dispose();
  }

  Future<void> _run(Future<String?> Function() action) async {
    if (!await _ensureName(context)) return;
    setState(() => _busy = true);
    final err = await action();
    if (!mounted) return;
    setState(() => _busy = false);
    if (err != null) toast(context, err);
  }

  @override
  Widget build(BuildContext context) {
    final jam = context.watch<JamController>();
    final p = Palette.of(context);
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 32),
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: p.ink,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Icon(AppIcons.jamOn, color: p.onInk, size: 36),
          ),
        ),
        const SizedBox(height: 18),
        _title('Jam'),
        const SizedBox(height: 6),
        Text(
          'Listen together — in the same room or from anywhere. Everyone adds songs, and the music stays in step.',
          style: TextStyle(color: p.sub, height: 1.45),
        ),
        const SizedBox(height: 18),
        Row(
          children: [
            Expanded(
              child: Text(
                jam.hasName
                    ? 'Friends see you as ${jam.myName}'
                    : 'Pick the name friends will see',
                style: TextStyle(color: p.sub),
              ),
            ),
            TextButton(
              onPressed: () async {
                final n = await _askName(context, jam.myName);
                if (n != null && n.trim().isNotEmpty) jam.setName(n);
              },
              child: Text(jam.hasName ? 'Change' : 'Set name'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        PillButton(
          icon: AppIcons.broadcast,
          label: _busy ? 'Starting…' : 'Start a Jam',
          onPressed: _busy
              ? null
              : () async {
                  final online = await _pickMode(context);
                  if (online == null) return;
                  await _run(() async {
                    final ok = await jam.startHosting(online: online);
                    return ok ? null : jam.error;
                  });
                },
        ),
        const SizedBox(height: 32),
        const Text(
          'Join a Jam nearby',
          style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 4),
        Text(
          'Be on the same Wi-Fi as the host, or on their hotspot.',
          style: TextStyle(color: p.sub, height: 1.4),
        ),
        const SizedBox(height: 12),
        if (jam.nearby.isEmpty)
          _card(
            context,
            Row(
              children: [
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    jam.lookingFailed
                        ? 'Can\'t look for Jams right now. Use a code instead.'
                        : 'Looking for Jams…',
                    style: TextStyle(color: p.sub),
                  ),
                ),
              ],
            ),
          ),
        for (final f in jam.nearby.values)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Material(
              color: p.card,
              borderRadius: BorderRadius.circular(18),
              child: ListTile(
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(18),
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 6,
                ),
                leading: CircleAvatar(
                  backgroundColor: p.ink,
                  child: Icon(AppIcons.jam, color: p.onInk, size: 20),
                ),
                title: Text(
                  f.name,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                subtitle: Text(
                  '${f.host} · ${count(f.people, 'person', 'people')}',
                ),
                trailing: FilledButton(
                  onPressed: _busy
                      ? null
                      : () => _run(() => jam.join(f.address, f.port)),
                  child: const Text('Join'),
                ),
              ),
            ),
          ),
        const SizedBox(height: 16),
        Text(
          'Have a code? Online Jams work from anywhere.',
          style: TextStyle(color: p.sub),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _code,
                textCapitalization: TextCapitalization.characters,
                autocorrect: false,
                decoration: const InputDecoration(
                  hintText: 'Jam code',
                  prefixIcon: Icon(AppIcons.link, size: 20),
                ),
                onSubmitted: (v) => _run(() => jam.joinWithCode(v)),
              ),
            ),
            const SizedBox(width: 10),
            FilledButton(
              onPressed: _busy
                  ? null
                  : () => _run(() => jam.joinWithCode(_code.text)),
              child: const Text('Join'),
            ),
          ],
        ),
      ],
    );
  }
}

// ---- Hosting ---------------------------------------------------------------

class _Hosting extends StatelessWidget {
  const _Hosting();

  @override
  Widget build(BuildContext context) {
    final jam = context.watch<JamController>();
    final player = context.watch<PlayerController>();
    final p = Palette.of(context);
    final now = player.current;
    final order = player.player.effectiveIndices;
    final at = order.indexOf(player.currentIndex);
    final upcoming = <Track>[
      if (at >= 0)
        for (final i in order.skip(at + 1).take(30))
          if (i < player.queue.length) player.queue[i],
    ];
    final people = jam.guests.length + 1;

    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 32),
      children: [
        _title(jam.jamName),
        const SizedBox(height: 4),
        Text(
          'You\'re hosting · ${count(people, 'person', 'people')}',
          style: TextStyle(color: p.sub),
        ),
        _eventPill(context, jam.event),
        const SizedBox(height: 18),
        _card(
          context,
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    jam.online ? AppIcons.broadcast : AppIcons.wifi,
                    size: 18,
                    color: p.sub,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      jam.online ? 'Online Jam' : 'Invite friends',
                      style: TextStyle(color: p.sub),
                    ),
                  ),
                  if (jam.online && jam.joinCode != null)
                    TextButton.icon(
                      icon: const Icon(AppIcons.link, size: 16),
                      label: const Text('Copy invite'),
                      onPressed: () {
                        Clipboard.setData(
                          ClipboardData(
                            text:
                                'Join my Jam on Musicly: open Jam, tap "Have a code?" and enter ${jam.joinCode}',
                          ),
                        );
                        toast(context, 'Invite copied — paste it in a chat');
                      },
                    ),
                ],
              ),
              const SizedBox(height: 8),
              if (jam.online && jam.joinCode != null) ...[
                SelectableText(
                  jam.joinCode!,
                  style: const TextStyle(
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 1.5,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Friends anywhere open Jam in Musicly and enter this code. They hear your songs on their own phones, in step with you.',
                  style: TextStyle(color: p.sub, height: 1.4),
                ),
              ] else if (jam.joinCode != null) ...[
                SelectableText(
                  jam.joinCode!,
                  style: const TextStyle(
                    fontSize: 30,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 2,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  'Friends on your Wi-Fi or hotspot open Jam in Musicly and tap Join — or type this code.',
                  style: TextStyle(color: p.sub, height: 1.4),
                ),
              ] else
                Text(
                  'Turn on Wi-Fi or your hotspot so friends can join.',
                  style: TextStyle(color: p.sub, height: 1.4),
                ),
            ],
          ),
        ),
        if (jam.online) ...[
          const SizedBox(height: 10),
          Row(
            children: [
              if (jam.sharingTitle != null) ...[
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Text(
                  jam.sharingTitle != null
                      ? 'Sharing ${jam.sharingTitle} with friends…'
                      : 'Songs are shared as private links that delete themselves within 12 hours.',
                  style: TextStyle(color: p.sub, fontSize: 12, height: 1.4),
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 18),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            Chip(
              avatar: const Icon(AppIcons.crown, size: 16),
              label: Text(jam.hasName ? '${jam.myName} (you)' : 'You'),
            ),
            for (final g in jam.guests)
              InputChip(
                label: Text(g.name),
                onDeleted: () => jam.removeGuest(g.id),
                deleteButtonTooltipMessage: 'Remove ${g.name}',
              ),
          ],
        ),
        const SizedBox(height: 8),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Friends can control playback'),
          subtitle: const Text('Play, pause, skip and seek'),
          value: jam.guestsControl,
          onChanged: jam.setGuestsControl,
        ),
        const SizedBox(height: 12),
        if (now == null)
          _card(
            context,
            Text(
              'Play something — it plays here for everyone, and friends\' songs join the queue.',
              style: TextStyle(color: p.sub, height: 1.45),
            ),
          )
        else ...[
          const Text(
            'Now playing',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 10),
          _SongRow(track: now, by: jam.addedBy(now), big: true),
        ],
        if (upcoming.isNotEmpty) ...[
          const SizedBox(height: 22),
          const Text(
            'Up next',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          for (final t in upcoming) _SongRow(track: t, by: jam.addedBy(t)),
        ],
        const SizedBox(height: 24),
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(
            foregroundColor: _red,
            side: const BorderSide(color: _red),
            minimumSize: const Size.fromHeight(48),
          ),
          icon: const Icon(AppIcons.close),
          label: const Text('End Jam'),
          onPressed: () => jam.leave(),
        ),
      ],
    );
  }
}

class _SongRow extends StatelessWidget {
  const _SongRow({required this.track, this.by, this.big = false});
  final Track track;
  final String? by;
  final bool big;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Artwork(track, size: big ? 64 : 46, radius: big ? 14 : 10),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  track.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: big ? 17 : 15,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  by == null ? track.artist : '${track.artist} · Added by $by',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.sub, fontSize: 13),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---- Joined ----------------------------------------------------------------

class _Joined extends StatefulWidget {
  const _Joined();
  @override
  State<_Joined> createState() => _JoinedState();
}

class _JoinedState extends State<_Joined> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    // Moves the progress bar between the host's updates.
    _tick = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => mounted ? setState(() {}) : null,
    );
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final jam = context.watch<JamController>();
    final p = Palette.of(context);
    final s = jam.state;
    final now = s.now;
    final pos = jam.position;
    final dur = now?.durationMs == null
        ? null
        : Duration(milliseconds: now!.durationMs!);

    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 32),
      children: [
        _title(jam.jamName),
        const SizedBox(height: 4),
        Text(
          '${jam.online ? 'Online · ' : ''}Hosted by ${jam.hostName} · ${count(s.people.length, 'person', 'people')}',
          style: TextStyle(color: p.sub),
        ),
        const SizedBox(height: 12),
        _people(context, s.people),
        _eventPill(context, jam.event),
        const SizedBox(height: 22),
        if (now == null)
          _card(
            context,
            Text(
              'Nothing is playing yet. Add a song to get things going!',
              style: TextStyle(color: p.sub, height: 1.45),
            ),
          )
        else ...[
          Center(
            child: Artwork(
              jam.viewTrack(now),
              size: 240,
              radius: 24,
              shadow: true,
            ),
          ),
          const SizedBox(height: 18),
          Text(
            now.title,
            textAlign: TextAlign.center,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 4),
          Text(
            now.by == null ? now.artist : '${now.artist} · Added by ${now.by}',
            textAlign: TextAlign.center,
            style: TextStyle(color: p.sub),
          ),
          const SizedBox(height: 16),
          if (dur != null && dur.inMilliseconds > 0) ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: (pos.inMilliseconds / dur.inMilliseconds).clamp(0, 1),
                minHeight: 4,
              ),
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Text(fmt(pos), style: TextStyle(color: p.sub, fontSize: 12)),
                const Spacer(),
                Text(fmt(dur), style: TextStyle(color: p.sub, fontSize: 12)),
              ],
            ),
          ],
          const SizedBox(height: 6),
          if (s.guestsControl)
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  iconSize: 30,
                  icon: const Icon(AppIcons.prev),
                  onPressed: () => jam.control('previous'),
                ),
                const SizedBox(width: 12),
                IconButton.filled(
                  iconSize: 34,
                  style: IconButton.styleFrom(
                    backgroundColor: p.ink,
                    foregroundColor: p.onInk,
                    fixedSize: const Size(64, 64),
                  ),
                  icon: Icon(s.playing ? AppIcons.pause : AppIcons.play),
                  onPressed: () => jam.control('toggle'),
                ),
                const SizedBox(width: 12),
                IconButton(
                  iconSize: 30,
                  icon: const Icon(AppIcons.next),
                  onPressed: () => jam.control('next'),
                ),
              ],
            )
          else if (!jam.online)
            Text(
              s.playing
                  ? 'Playing on ${jam.hostName}\'s phone'
                  : 'Paused on ${jam.hostName}\'s phone',
              textAlign: TextAlign.center,
              style: TextStyle(color: p.sub, fontSize: 13),
            ),
          if (jam.waitingForSong)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                '${jam.hostName} is sharing this song…',
                textAlign: TextAlign.center,
                style: TextStyle(color: p.sub, fontSize: 13),
              ),
            ),
        ],
        if (jam.online) ...[
          const SizedBox(height: 10),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            secondary: const Icon(AppIcons.headphones),
            title: const Text('Listen on this phone'),
            subtitle: const Text('In step with the host'),
            value: jam.listenHere,
            onChanged: jam.setListenHere,
          ),
        ],
        const SizedBox(height: 22),
        PillButton(
          icon: AppIcons.add,
          label: 'Add songs',
          onPressed: () => showModalBottomSheet(
            context: context,
            useRootNavigator: true,
            isScrollControlled: true,
            useSafeArea: true,
            builder: (_) => const _AddSongs(),
          ),
        ),
        if (s.queue.isNotEmpty) ...[
          const SizedBox(height: 26),
          const Text(
            'Up next',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          for (final t in s.queue) _SongRow(track: jam.viewTrack(t), by: t.by),
        ],
        const SizedBox(height: 24),
        OutlinedButton.icon(
          style: OutlinedButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
          ),
          icon: const Icon(AppIcons.leave),
          label: const Text('Leave Jam'),
          onPressed: () => jam.leave(),
        ),
      ],
    );
  }
}

/// Pick songs for the Jam: from the host's library, or send your own.
class _AddSongs extends StatefulWidget {
  const _AddSongs();
  @override
  State<_AddSongs> createState() => _AddSongsState();
}

class _AddSongsState extends State<_AddSongs> {
  bool _mine = false;
  String _query = '';
  Timer? _debounce;
  Future<List<JamTrack>>? _hostSongs;

  @override
  void initState() {
    super.initState();
    _hostSongs = context.read<JamController>().searchHost('');
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _search(String q) {
    _query = q;
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (!mounted) return;
      setState(() {
        if (!_mine) {
          _hostSongs = context.read<JamController>().searchHost(q);
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final jam = context.watch<JamController>();
    final p = Palette.of(context);
    if (!jam.active) {
      // The Jam ended while this was open.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.maybePop(context);
      });
    }
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.85,
      maxChildSize: 0.95,
      builder: (context, scroll) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
            child: Column(
              children: [
                SizedBox(
                  width: double.infinity,
                  child: SegmentedButton<bool>(
                    showSelectedIcon: false,
                    segments: [
                      ButtonSegment(
                        value: false,
                        label: Text('${jam.hostName}\'s songs'),
                      ),
                      const ButtonSegment(value: true, label: Text('My songs')),
                    ],
                    selected: {_mine},
                    onSelectionChanged: (v) => setState(() {
                      _mine = v.first;
                      if (!_mine) _hostSongs = jam.searchHost(_query);
                    }),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  decoration: const InputDecoration(
                    hintText: 'Search songs or artists',
                    prefixIcon: Icon(AppIcons.search, size: 20),
                  ),
                  onChanged: _search,
                ),
                if (_mine)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      jam.online
                          ? 'Your song is shared as a private link and added to the queue.'
                          : 'Your song is sent to ${jam.hostName}\'s phone and added to the queue.',
                      style: TextStyle(color: p.sub, fontSize: 12),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(child: _mine ? _myList(context, scroll) : _hostList(scroll)),
        ],
      ),
    );
  }

  Widget _hostList(ScrollController scroll) {
    final jam = context.read<JamController>();
    return FutureBuilder<List<JamTrack>>(
      future: _hostSongs,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        final songs = snap.data ?? const [];
        if (songs.isEmpty) {
          return Center(
            child: Text(
              'No songs found',
              style: TextStyle(color: Palette.of(context).sub),
            ),
          );
        }
        return ListView.builder(
          controller: scroll,
          itemCount: songs.length,
          itemBuilder: (_, i) {
            final t = songs[i];
            return _PickRow(
              track: jam.viewTrack(t),
              onAdd: (next) {
                jam.addFromHost(t, next: next);
                toast(context, 'Added ${t.title}');
              },
            );
          },
        );
      },
    );
  }

  Widget _myList(BuildContext context, ScrollController scroll) {
    final jam = context.watch<JamController>();
    final lib = context.read<LibraryController>();
    final q = _query.trim().toLowerCase();
    final songs = lib.allSongs
        .where(
          (t) =>
              q.isEmpty ||
              t.title.toLowerCase().contains(q) ||
              t.artist.toLowerCase().contains(q),
        )
        .toList();
    if (songs.isEmpty) {
      return Center(
        child: Text(
          'No songs on this phone yet',
          style: TextStyle(color: Palette.of(context).sub),
        ),
      );
    }
    return ListView.builder(
      controller: scroll,
      itemCount: songs.length,
      itemBuilder: (_, i) {
        final t = songs[i];
        return _PickRow(
          track: t,
          progress: jam.sending[t.id],
          onAdd: (next) async {
            final err = await jam.send(t, next: next);
            if (context.mounted) toast(context, err ?? 'Sent ${t.title}');
          },
        );
      },
    );
  }
}

class _PickRow extends StatelessWidget {
  const _PickRow({required this.track, required this.onAdd, this.progress});
  final Track track;
  final void Function(bool next) onAdd;

  /// Upload progress while sending, 0..1.
  final double? progress;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20),
      leading: Artwork(track, size: 46, radius: 10),
      title: Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        track.artist,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(color: p.sub),
      ),
      onTap: progress == null ? () => onAdd(false) : null,
      trailing: progress != null
          ? SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(
                strokeWidth: 2.5,
                value: progress! > 0 ? progress : null,
              ),
            )
          : PopupMenuButton<bool>(
              icon: const Icon(AppIcons.add),
              tooltip: 'Add',
              onSelected: onAdd,
              itemBuilder: (_) => const [
                PopupMenuItem(value: false, child: Text('Add to queue')),
                PopupMenuItem(value: true, child: Text('Play next')),
              ],
            ),
    );
  }
}

/// "In this room" or "Online"; null if the sheet was dismissed.
Future<bool?> _pickMode(BuildContext context) => showModalBottomSheet<bool>(
  context: context,
  useRootNavigator: true,
  builder: (ctx) => SafeArea(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(8, 4, 8, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const ListTile(
            title: Text(
              'Where are your friends?',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
          ),
          ListTile(
            leading: const Icon(AppIcons.wifi),
            title: const Text(
              'In this room',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            subtitle: const Text(
              'Same Wi-Fi or your hotspot. Music plays from your phone; no internet needed.',
            ),
            onTap: () => Navigator.pop(ctx, false),
          ),
          ListTile(
            leading: const Icon(AppIcons.broadcast),
            title: const Text(
              'Online — anywhere',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            subtitle: const Text(
              'Friends hear your songs on their own phones, in step. Uses internet data.',
            ),
            onTap: () => Navigator.pop(ctx, true),
          ),
        ],
      ),
    ),
  ),
);
