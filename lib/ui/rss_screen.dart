import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/rss_feed.dart';
import '../models/track.dart';
import '../state/library_controller.dart';
import 'collection_screen.dart';
import 'icons.dart';
import 'nav.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// RSS feeds: paste a link to a podcast or music feed and stream its songs.
class RssScreen extends StatelessWidget {
  const RssScreen({super.key});

  Future<void> _add(BuildContext context) async {
    final feed = await showDialog<RssFeed>(
      context: context,
      builder: (_) => const _AddFeedDialog(),
    );
    if (feed != null && context.mounted) toast(context, 'Added ${feed.title}');
  }

  void _open(BuildContext context, RssFeed f) {
    final lib = context.read<LibraryController>();
    openPage(
      context,
      CollectionScreen(
        title: f.title,
        owner: Uri.tryParse(f.url)?.host ?? 'RSS feed',
        kind: 'RSS feed',
        cover: f.imageUrl == null
            ? null
            : Track(
                id: 'rssfeed:${f.url}',
                title: f.title,
                artist: '',
                source: TrackSource.feed,
                artworkUrl: f.imageUrl,
              ),
        load: () async => (await lib.rss.load(f.url)).tracks,
      ),
    );
  }

  Future<void> _remove(BuildContext context, RssFeed f) async {
    final lib = context.read<LibraryController>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Remove ${f.title}?'),
        content: const Text(
          'Its songs stop showing here. You can add it again any time.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (ok == true) lib.removeFeed(f);
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    Widget cover(RssFeed f) => ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: 56,
        height: 56,
        color: p.card,
        child: f.imageUrl == null
            ? Icon(AppIcons.broadcast, color: p.sub)
            : Image.network(
                f.imageUrl!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) =>
                    Icon(AppIcons.broadcast, color: p.sub),
              ),
      ),
    );

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
        title: const Text('RSS feeds'),
        actions: [
          IconButton(
            tooltip: 'Add a feed',
            icon: const Icon(AppIcons.add),
            onPressed: () => _add(context),
          ),
        ],
      ),
      body: lib.feeds.isEmpty
          ? EmptyState(
              icon: AppIcons.broadcast,
              text: 'Add the link of an RSS feed with audio in it, such as a podcast or a music mix, and stream it here.',
              action: FilledButton.icon(
                onPressed: () => _add(context),
                icon: const Icon(AppIcons.add, size: 18),
                label: const Text('Add a feed'),
              ),
            )
          : ListView(
              padding: const EdgeInsets.only(bottom: 24),
              children: [
                for (final f in lib.feeds)
                  InkWell(
                    onTap: () => _open(context, f),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 8,
                      ),
                      child: Row(
                        children: [
                          cover(f),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  f.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
                                    fontSize: 16,
                                  ),
                                ),
                                const SizedBox(height: 3),
                                Text(
                                  Uri.tryParse(f.url)?.host ?? f.url,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(color: p.sub, fontSize: 13),
                                ),
                              ],
                            ),
                          ),
                          IconButton(
                            tooltip: 'Remove',
                            icon: Icon(AppIcons.trash, color: p.sub, size: 20),
                            onPressed: () => _remove(context, f),
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
    );
  }
}

class _AddFeedDialog extends StatefulWidget {
  const _AddFeedDialog();
  @override
  State<_AddFeedDialog> createState() => _AddFeedDialogState();
}

class _AddFeedDialogState extends State<_AddFeedDialog> {
  final _ctl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _ctl.dispose();
    super.dispose();
  }

  Future<void> _go() async {
    if (_ctl.text.trim().isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final feed = await context.read<LibraryController>().addFeed(_ctl.text);
      if (mounted) Navigator.pop(context, feed);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.toString().replaceFirst('Exception: ', '');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Add an RSS feed'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextField(
          controller: _ctl,
          autofocus: true,
          keyboardType: TextInputType.url,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(hintText: 'Paste the feed link'),
          onSubmitted: (_) => _go(),
        ),
        if (_error != null) ...[
          const SizedBox(height: 10),
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ],
      ],
    ),
    actions: [
      TextButton(
        onPressed: _busy ? null : () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        onPressed: _busy ? null : _go,
        child: _busy
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Text('Add'),
      ),
    ],
  );
}
