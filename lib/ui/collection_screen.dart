import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'routes.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Album / playlist page from the design: cover on the left, meta, big
/// title, underlined artist, Play & Shuffle and a numbered list.
class CollectionScreen extends StatefulWidget {
  const CollectionScreen({
    super.key,
    required this.title,
    required this.owner,
    this.ownerArtistId,
    this.kind = 'Playlist',
    this.year,
    this.cover,
    this.load,
    this.live,
    this.removableFiles = false,
    this.actions = const [],
    this.onRefresh,
    this.banner,
  }) : playlistId = null;

  /// A playlist the user made: editable, reorderable.
  const CollectionScreen.user({super.key, required String this.playlistId})
    : title = '',
      owner = 'You',
      ownerArtistId = null,
      kind = 'Your playlist',
      year = null,
      cover = null,
      load = null,
      live = null,
      removableFiles = false,
      actions = const [],
      onRefresh = null,
      banner = null;

  final String title;
  final String owner;
  final String? ownerArtistId;
  final String kind;
  final int? year;
  final Track? cover;
  final Future<List<Track>> Function()? load;
  final List<Track> Function(LibraryController c)? live;
  final bool removableFiles;
  final String? playlistId;

  /// Extra app-bar buttons, e.g. channel settings.
  final List<Widget> actions;

  /// Enables pull-to-refresh.
  final Future<void> Function()? onRefresh;

  /// Shown between the Play/Shuffle buttons and the songs.
  final Widget? banner;

  @override
  State<CollectionScreen> createState() => _CollectionScreenState();
}

enum _Sort { original, title, artist, duration }

class _CollectionScreenState extends State<CollectionScreen> {
  Future<List<Track>>? _future;
  bool _searching = false;
  bool _editing = false;
  String _q = '';
  _Sort _sort = _Sort.original;

  @override
  void initState() {
    super.initState();
    _future = widget.load?.call();
  }

  List<Track> _apply(List<Track> all) {
    final q = _q.toLowerCase();
    final l = all
        .where(
          (t) =>
              q.isEmpty ||
              t.title.toLowerCase().contains(q) ||
              t.artist.toLowerCase().contains(q),
        )
        .toList();
    switch (_sort) {
      case _Sort.title:
        l.sort(
          (a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
        );
      case _Sort.artist:
        l.sort(
          (a, b) => a.artist.toLowerCase().compareTo(b.artist.toLowerCase()),
        );
      case _Sort.duration:
        l.sort(
          (a, b) => (a.duration ?? Duration.zero).compareTo(
            b.duration ?? Duration.zero,
          ),
        );
      case _Sort.original:
        break;
    }
    return l;
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    if (widget.playlistId != null) {
      final p = lib.playlist(widget.playlistId!);
      if (p == null) {
        return const Scaffold(
          body: EmptyState(icon: AppIcons.playNext, text: 'Playlist deleted.'),
        );
      }
      return _body(context, p.tracks, false, null, user: p);
    }
    if (widget.live != null) {
      return _body(context, widget.live!(lib), false, null);
    }
    return FutureBuilder<List<Track>>(
      future: _future,
      builder: (context, snap) => _body(
        context,
        snap.data ?? const [],
        snap.connectionState != ConnectionState.done,
        snap.hasError ? 'Could not load songs.' : null,
      ),
    );
  }

  Widget _body(
    BuildContext context,
    List<Track> all,
    bool loading,
    String? error, {
    UserPlaylist? user,
  }) {
    final c = context.read<PlayerController>();
    final lib = context.read<LibraryController>();
    final p = Palette.of(context);
    final tracks = _apply(all);
    final title = user?.name ?? widget.title;
    final meta = [
      widget.kind,
      if (!loading) '${all.length} ${all.length == 1 ? 'song' : 'songs'}',
      if (widget.year != null) '${widget.year}',
    ].join(' • ');
    final canReorder =
        user != null && _editing && _q.isEmpty && _sort == _Sort.original;

    final header = <Widget>[
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            user != null
                ? Mosaic(user.tracks, size: 112, radius: 14)
                : Artwork(
                    widget.cover ?? (all.isNotEmpty ? all.first : null),
                    size: 112,
                    radius: 14,
                    shadow: true,
                  ),
            const SizedBox(width: 18),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(meta, style: TextStyle(color: p.sub, fontSize: 12)),
                  const SizedBox(height: 4),
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.6,
                      height: 1.15,
                    ),
                  ),
                  const SizedBox(height: 4),
                  GestureDetector(
                    onTap: widget.ownerArtistId == null
                        ? null
                        : () => openArtist(
                            context,
                            widget.ownerArtistId!,
                            widget.owner,
                          ),
                    child: Text(
                      user != null ? 'You' : widget.owner,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: p.sub,
                        decoration: TextDecoration.underline,
                        decorationColor: p.sub,
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      _OutlineIcon(
                        icon: AppIcons.addQueue,
                        tooltip: 'Add all to queue',
                        onTap: tracks.isEmpty
                            ? null
                            : () {
                                c.addToQueue(tracks);
                                toast(
                                  context,
                                  'Added ${tracks.length} songs to queue',
                                );
                              },
                      ),
                      const SizedBox(width: 10),
                      _OutlineIcon(
                        icon: user != null
                            ? (_editing ? AppIcons.check : AppIcons.edit)
                            : AppIcons.addPlaylist,
                        tooltip: user != null ? 'Edit' : 'Save to a playlist',
                        onTap: user != null
                            ? () => setState(() => _editing = !_editing)
                            : tracks.isEmpty
                            ? null
                            : () => showAddToPlaylist(
                                context,
                                tracks,
                                suggestedName: title,
                              ),
                      ),
                      const SizedBox(width: 10),
                      PopupMenuButton<String>(
                        tooltip: 'More',
                        onSelected: (v) {
                          switch (v) {
                            case 'like':
                              lib.favoriteAll(tracks);
                              toast(context, 'Added to Liked songs');
                            case 'rename':
                              _rename(context, user!);
                            case 'delete':
                              _delete(context, user!);
                            default:
                              setState(() => _sort = _Sort.values.byName(v));
                          }
                        },
                        itemBuilder: (_) => [
                          const PopupMenuItem(
                            value: 'like',
                            child: Text('Like all songs'),
                          ),
                          if (user != null) ...[
                            const PopupMenuItem(
                              value: 'rename',
                              child: Text('Rename playlist'),
                            ),
                            const PopupMenuItem(
                              value: 'delete',
                              child: Text('Delete playlist'),
                            ),
                          ],
                          const PopupMenuDivider(),
                          for (final s in _Sort.values)
                            CheckedPopupMenuItem(
                              value: s.name,
                              checked: _sort == s,
                              child: Text(switch (s) {
                                _Sort.original => 'Default order',
                                _Sort.title => 'Sort by title',
                                _Sort.artist => 'Sort by artist',
                                _Sort.duration => 'Sort by length',
                              }),
                            ),
                        ],
                        child: const _OutlineIcon(
                          icon: AppIcons.more,
                          wide: true,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: Row(
          children: [
            Expanded(
              child: PillButton(
                icon: AppIcons.playCircle,
                label: 'Play',
                onPressed: tracks.isEmpty ? null : () => c.playQueue(tracks, 0),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: PillButton(
                icon: AppIcons.shuffle,
                label: 'Shuffle',
                filled: false,
                onPressed: tracks.isEmpty ? null : () => c.playShuffled(tracks),
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 12),
    ];

    Widget row(int i) => NumberedTrackRow(
      key: ValueKey(tracks[i].id),
      index: i,
      track: tracks[i],
      onTap: () => c.playQueue(tracks, i),
      onRemove: user != null
          ? () => lib.removeFromPlaylist(user, tracks[i])
          : widget.removableFiles && tracks[i].source == TrackSource.file
          ? () => lib.removeFile(tracks[i])
          : null,
      removeLabel: user != null ? 'Remove from this playlist' : null,
      trailing: canReorder
          ? ReorderableDragStartListener(
              index: i,
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Icon(AppIcons.drag, color: p.sub),
              ),
            )
          : null,
    );

    Widget list;
    if (loading) {
      list = const SliverToBoxAdapter(
        child: Padding(
          padding: EdgeInsets.all(40),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    } else if (error != null) {
      list = SliverToBoxAdapter(
        child: EmptyState(icon: AppIcons.offline, text: error),
      );
    } else if (tracks.isEmpty) {
      list = SliverToBoxAdapter(
        child: EmptyState(
          icon: AppIcons.music,
          text: _q.isNotEmpty
              ? 'No songs match "$_q".'
              : user != null
              ? 'This playlist is empty.\nUse ⋯ on any song and choose "Add to playlist".'
              : 'Nothing here yet.',
        ),
      );
    } else if (canReorder) {
      list = SliverReorderableList(
        itemCount: tracks.length,
        onReorderItem: (from, to) => lib.reorderPlaylist(user, from, to),
        itemBuilder: (_, i) => Material(
          key: ValueKey(tracks[i].id),
          color: Colors.transparent,
          child: row(i),
        ),
      );
    } else {
      list = SliverList.builder(
        itemCount: tracks.length,
        itemBuilder: (_, i) => row(i),
      );
    }

    final scroll = CustomScrollView(
      physics: const AlwaysScrollableScrollPhysics(),
      slivers: [
        SliverList.list(children: header),
        if (widget.banner != null) SliverToBoxAdapter(child: widget.banner),
        list,
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ],
    );

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
        title: _searching
            ? TextField(
                autofocus: true,
                onChanged: (v) => setState(() => _q = v),
                decoration: const InputDecoration(
                  hintText: 'Find in list',
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 10,
                  ),
                ),
              )
            : null,
        actions: [
          IconButton(
            icon: Icon(_searching ? AppIcons.close : AppIcons.search),
            onPressed: () => setState(() {
              _searching = !_searching;
              _q = '';
            }),
          ),
          ...widget.actions,
        ],
      ),
      body: widget.onRefresh == null
          ? scroll
          : RefreshIndicator(onRefresh: widget.onRefresh!, child: scroll),
    );
  }

  Future<void> _rename(BuildContext context, UserPlaylist p) async {
    final name = await promptText(
      context,
      'Rename playlist',
      initial: p.name,
      action: 'Save',
    );
    if (name != null && context.mounted) {
      context.read<LibraryController>().renamePlaylist(p, name);
    }
  }

  Future<void> _delete(BuildContext context, UserPlaylist p) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete "${p.name}"?'),
        content: const Text('The songs stay in your library.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok == true && context.mounted) {
      context.read<LibraryController>().deletePlaylist(p);
      Navigator.maybePop(context);
    }
  }
}

class _OutlineIcon extends StatelessWidget {
  const _OutlineIcon({
    required this.icon,
    this.onTap,
    this.tooltip,
    this.wide = false,
  });
  final IconData icon;
  final VoidCallback? onTap;
  final String? tooltip;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final box = Container(
      width: wide ? 40 : 30,
      height: 26,
      decoration: BoxDecoration(
        border: Border.all(color: p.ink, width: 1.4),
        borderRadius: BorderRadius.circular(wide ? 13 : 8),
      ),
      child: Icon(icon, size: 15),
    );
    if (onTap == null && tooltip == null) return box;
    return Tooltip(
      message: tooltip ?? '',
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: box,
      ),
    );
  }
}
