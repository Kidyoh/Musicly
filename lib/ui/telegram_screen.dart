import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../state/library_controller.dart';
import 'collection_screen.dart';
import 'icons.dart';
import 'sheets.dart';
import 'theme.dart';
import 'widgets.dart';

/// Your Telegram channel, read straight from Telegram with a bot token.
/// No server: the token is typed in here and stays on this phone.
class TelegramScreen extends StatelessWidget {
  const TelegramScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    if (!lib.channelConnected) return const _ConnectView();
    return CollectionScreen(
      key: ValueKey(lib.channelName),
      title: lib.channelName ?? 'Telegram',
      owner: 'Your Telegram channel',
      kind: 'Channel',
      live: (l) => l.channelTracks,
      onRefresh: lib.syncTelegram,
      actions: const [_ChannelMenuButton()],
      banner: const _ChannelBanner(),
    );
  }
}

/// Import progress, errors, and how to add older songs.
class _ChannelBanner extends StatelessWidget {
  const _ChannelBanner();

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryController>();
    final p = Palette.of(context);
    Widget card(Widget child) => Container(
      margin: const EdgeInsets.fromLTRB(20, 4, 20, 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: p.card,
        borderRadius: BorderRadius.circular(16),
      ),
      child: child,
    );

    if (lib.importing) {
      return card(
        Row(
          children: [
            const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                'Importing older posts… checked ${lib.importScanned}, found ${lib.importFound} songs',
                style: TextStyle(color: p.sub),
              ),
            ),
            TextButton(onPressed: lib.cancelImport, child: const Text('Stop')),
          ],
        ),
      );
    }
    if (lib.channelError != null) {
      return card(
        Text(
          lib.channelError!,
          style: const TextStyle(color: Color(0xFFE5484D)),
        ),
      );
    }
    if (lib.channelTracks.isEmpty) {
      return card(
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Connected! Waiting for songs',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(
              'New audio posts appear here automatically. To add songs posted earlier, '
              'open the ⋯ menu and choose "Import older songs", or forward them to @${lib.botUsername}.',
              style: TextStyle(color: p.sub, height: 1.45),
            ),
          ],
        ),
      );
    }
    if (lib.tooBigSkipped > 0) {
      return card(
        Text(
          '${lib.tooBigSkipped} file${lib.tooBigSkipped == 1 ? ' is' : 's are'} over 20 MB, '
          'which Telegram doesn\'t let bots stream, so ${lib.tooBigSkipped == 1 ? 'it was' : 'they were'} skipped.',
          style: TextStyle(color: p.sub, height: 1.45),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}

class _ChannelMenuButton extends StatelessWidget {
  const _ChannelMenuButton();

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: 'Channel',
    icon: const Icon(AppIcons.telegram),
    onPressed: () => _menu(context),
  );

  void _menu(BuildContext context) {
    final lib = context.read<LibraryController>();
    showModalBottomSheet(
      context: context,
      useRootNavigator: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.telegramOn),
              title: Text(
                lib.channelName ?? 'Telegram',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              subtitle: Text('Read by @${lib.botUsername ?? 'your bot'}'),
            ),
            const Divider(),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.refresh),
              title: const Text('Check for new songs'),
              onTap: () {
                Navigator.pop(ctx);
                lib.syncTelegram();
              },
            ),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.history),
              title: const Text('Import older songs'),
              subtitle: Text(
                lib.canImport
                    ? 'Reads the channel\'s earlier posts'
                    : 'First open @${lib.botUsername} in Telegram and press Start',
              ),
              onTap: () async {
                Navigator.pop(ctx);
                if (!lib.canImport) {
                  await lib.syncTelegram(); // maybe they just pressed Start
                }
                if (lib.canImport) {
                  lib.importTelegramHistory();
                } else if (context.mounted) {
                  _openBot(lib.botUsername);
                  toast(
                    context,
                    'Press Start in the bot chat, then come back and import.',
                  );
                }
              },
            ),
            ListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 24),
              leading: const Icon(AppIcons.close),
              title: const Text('Disconnect channel'),
              onTap: () {
                Navigator.pop(ctx);
                lib.disconnectTelegram();
              },
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

void _openBot(String? username) {
  if (username == null) return;
  launchUrl(
    Uri.parse('https://t.me/$username?start=musicly'),
    mode: LaunchMode.externalApplication,
  );
}

class _ConnectView extends StatefulWidget {
  const _ConnectView();
  @override
  State<_ConnectView> createState() => _ConnectViewState();
}

class _ConnectViewState extends State<_ConnectView> {
  final _token = TextEditingController();
  final _channel = TextEditingController();
  bool _busy = false;
  bool _showToken = false;
  String? _error;

  @override
  void dispose() {
    _token.dispose();
    _channel.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    if (_token.text.trim().isEmpty || _channel.text.trim().isEmpty) {
      setState(() => _error = 'Enter the bot token and your channel.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final lib = context.read<LibraryController>();
    final err = await lib.connectTelegram(_token.text, _channel.text);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _error = err;
    });
    if (err == null) toast(context, 'Connected to ${lib.channelName}');
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    Widget step(int n, String text) => Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 22,
            height: 22,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: p.ink, shape: BoxShape.circle),
            child: Text(
              '$n',
              style: TextStyle(
                color: p.onInk,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: TextStyle(color: p.sub, height: 1.45)),
          ),
        ],
      ),
    );

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(AppIcons.back),
          onPressed: () => Navigator.maybePop(context),
        ),
      ),
      body: ListView(
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
              child: Icon(AppIcons.telegramOn, color: p.onInk, size: 36),
            ),
          ),
          const SizedBox(height: 18),
          const Text(
            'Your Telegram channel',
            style: TextStyle(
              fontSize: 28,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.6,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'Play the full songs posted in your channel, straight from Telegram. Nothing to install or host.',
            style: TextStyle(color: p.sub, height: 1.45),
          ),
          const SizedBox(height: 24),
          step(
            1,
            'In Telegram, open @BotFather, send /newbot and follow the steps. Copy the token it gives you.',
          ),
          step(
            2,
            'Open your channel → Administrators → Add admin → pick your new bot.',
          ),
          step(3, 'Paste the token and your channel below.'),
          const SizedBox(height: 12),
          TextField(
            controller: _token,
            obscureText: !_showToken,
            autocorrect: false,
            decoration: InputDecoration(
              hintText: 'Bot token (123456:ABC-…)',
              prefixIcon: const Icon(AppIcons.key, size: 20),
              suffixIcon: IconButton(
                icon: Icon(
                  _showToken ? AppIcons.eyeOff : AppIcons.eye,
                  size: 18,
                ),
                tooltip: _showToken ? 'Hide' : 'Show',
                onPressed: () => setState(() => _showToken = !_showToken),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _channel,
            autocorrect: false,
            decoration: const InputDecoration(
              hintText: 'Channel: @name or t.me link',
              prefixIcon: Icon(AppIcons.link, size: 20),
            ),
            onSubmitted: (_) => _connect(),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                _error!,
                style: const TextStyle(color: Color(0xFFE5484D)),
              ),
            ),
          const SizedBox(height: 18),
          PillButton(
            icon: AppIcons.telegram,
            label: _busy ? 'Connecting…' : 'Connect channel',
            onPressed: _busy ? null : _connect,
          ),
          const SizedBox(height: 20),
          Text(
            'Telegram lets bots stream files up to 20 MB, which covers normal MP3 and M4A songs. '
            'Your token is saved only on this phone.',
            style: TextStyle(color: p.sub, fontSize: 12, height: 1.45),
          ),
        ],
      ),
    );
  }
}
