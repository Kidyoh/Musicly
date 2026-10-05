import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/collection.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import '../state/player_controller.dart';
import 'icons.dart';
import 'routes.dart';
import 'theme.dart';
import 'widgets.dart';

class ArtistScreen extends StatefulWidget {
  const ArtistScreen({super.key, required this.id, required this.name});
  final String id;
  final String name;
  @override
  State<ArtistScreen> createState() => _ArtistScreenState();
}

class _ArtistScreenState extends State<ArtistScreen> {
  late final Future<(Artist, List<Track>, List<Collection>, List<Artist>)>
  _data = _load();
  bool _allTop = false;

  Future<(Artist, List<Track>, List<Collection>, List<Artist>)> _load() async {
    final api = context.read<LibraryController>().api;
    final r = await Future.wait([
      api.artistInfo(widget.id),
      api.artistTop(widget.id, limit: 10),
      api.artistAlbums(widget.id),
      api.relatedArtists(widget.id),
    ]);
    return (
      r[0] as Artist,
      r[1] as List<Track>,
      r[2] as List<Collection>,
      r[3] as List<Artist>,
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
      ),
      body: FutureBuilder(
        future: _data,
        builder: (context, snap) {
          if (snap.hasError) {
            return const EmptyState(
              icon: AppIcons.offline,
              text: 'Could not load this artist.',
            );
          }
          if (!snap.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          final (artist, top, albums, related) = snap.data!;
          final c = context.read<PlayerController>();
          final lib = context.watch<LibraryController>();
          final following = lib.isFollowing(artist.id);
          final shown = _allTop ? top : top.take(5).toList();

          return ListView(
            padding: const EdgeInsets.only(bottom: 24),
            children: [
              Center(child: ArtistAvatar(artist, size: 168)),
              const SizedBox(height: 18),
              Text(
                artist.name,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 30,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.8,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                [
                  if (artist.fans != null) '${compact(artist.fans!)} fans',
                  if (artist.albums != null) '${artist.albums} albums',
                ].join('  •  '),
                textAlign: TextAlign.center,
                style: TextStyle(color: p.sub),
              ),
              const SizedBox(height: 18),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Row(
                  children: [
                    Expanded(
                      child: PillButton(
                        icon: AppIcons.playCircle,
                        label: 'Play',
                        onPressed: top.isEmpty
                            ? null
                            : () => c.playQueue(top, 0),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: PillButton(
                        icon: AppIcons.radio,
                        label: 'Radio',
                        filled: false,
                        onPressed: () async {
                          final radio = await lib.api.artistRadio(
                            artist.id,
                            limit: 40,
                          );
                          c.playQueue(radio, 0);
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    SizedBox(
                      height: 48,
                      child: OutlinedButton(
                        onPressed: () => lib.toggleFollow(artist),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: p.ink,
                          side: BorderSide(
                            color: following ? p.ink : p.line,
                            width: 1.4,
                          ),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10),
                          ),
                        ),
                        child: Icon(
                          following ? AppIcons.following : AppIcons.follow,
                          size: 20,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              if (top.isNotEmpty) ...[
                const SectionHeader('Popular'),
                for (var i = 0; i < shown.length; i++)
                  NumberedTrackRow(
                    index: i,
                    track: shown[i],
                    onTap: () => c.playQueue(top, i),
                  ),
                if (top.length > 5)
                  Center(
                    child: TextButton(
                      onPressed: () => setState(() => _allTop = !_allTop),
                      child: Text(
                        _allTop ? 'Show less' : 'Show all ${top.length}',
                        style: TextStyle(
                          color: p.sub,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
              ],
              if (albums.isNotEmpty) ...[
                const SectionHeader('Discography'),
                HRow(
                  height: 206,
                  children: [
                    for (final a in albums)
                      CoverCard(
                        title: a.title,
                        subtitle: a.year == null ? 'Album' : '${a.year}',
                        art: Artwork(a.coverTrack, size: 148, radius: 16),
                        onTap: () => openCollection(context, a),
                      ),
                  ],
                ),
              ],
              if (related.isNotEmpty) ...[
                const SectionHeader('Fans also like'),
                HRow(
                  height: 130,
                  children: [
                    for (final r in related)
                      ArtistBubble(
                        artist: r,
                        onTap: () => openArtist(context, r.id, r.name),
                      ),
                  ],
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}
