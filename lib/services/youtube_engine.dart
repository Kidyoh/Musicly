import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:youtube_player_iframe/youtube_player_iframe.dart';

/// Optional overlay for showing a YouTube video in-app.
///
/// Playback audio is streamed through [YouTubeApi] into just_audio. This
/// WebView is only for picture, if a [VideoSlot] is on screen.
class YouTubeEngine {
  YouTubeEngine._();
  static final instance = YouTubeEngine._();

  late final controller = YoutubePlayerController(
    params: const YoutubePlayerParams(
      showControls: false,
      showFullscreenButton: false,
      enableCaption: false,
      showVideoAnnotations: false,
      strictRelatedVideos: true,
      videoStateUpdateInterval: 250,
    ),
  );

  bool _started = false;
  Object? _slotOwner;

  /// Where the video is shown, in global coordinates. Null keeps it out of
  /// sight (it still plays).
  final slot = ValueNotifier<Rect?>(null);

  Stream<YoutubePlayerValue> get states => controller.stream;

  /// The player's own position while it plays.
  Stream<Duration> get positions =>
      controller.videoStateStream.map((s) => s.position);

  Future<void> _start() async {
    if (_started) return;
    _started = true;
    await controller.initWithParams(params: controller.params);
  }

  Future<void> load(String videoId, {Duration? from, required bool play}) {
    final start = from == null || from < const Duration(seconds: 1)
        ? null
        : from.inMilliseconds / 1000;
    return play
        ? controller.loadVideoById(videoId: videoId, startSeconds: start)
        : controller.cueVideoById(videoId: videoId, startSeconds: start);
  }

  Future<void> play() => controller.playVideo();
  Future<void> pause() => controller.pauseVideo();
  Future<void> stop() => controller.stopVideo();

  Future<void> seek(Duration to) => controller.seekTo(
    seconds: to.inMilliseconds / 1000,
    allowSeekAhead: true,
  );

  /// [v] from 0 to 1, like the audio player's volume.
  Future<void> setVolume(double v) =>
      controller.setVolume((v.clamp(0, 1) * 100).round());

  Future<void> setRate(double r) => controller.setPlaybackRate(r);
}

/// Hosts the YouTube player above the whole app: a speck in a corner while
/// it only plays sound, or laid over a [VideoSlot] to show the video.
class YouTubeStage extends StatefulWidget {
  const YouTubeStage({super.key, required this.child});
  final Widget child;

  @override
  State<YouTubeStage> createState() => _YouTubeStageState();
}

class _YouTubeStageState extends State<YouTubeStage> {
  final _engine = YouTubeEngine.instance;

  @override
  void initState() {
    super.initState();
    _engine._start();
  }

  @override
  Widget build(BuildContext context) {
    // Sized 1×1 rather than hidden: a WebView that isn't laid out stops.
    final web = IgnorePointer(
      child: WebViewWidget(
        key: const ValueKey('youtube-player'),
        controller: _engine.controller.webViewController,
      ),
    );
    return Stack(
      children: [
        widget.child,
        ValueListenableBuilder<Rect?>(
          valueListenable: _engine.slot,
          builder: (_, rect, child) => rect == null
              ? Positioned(left: 0, bottom: 0, width: 1, height: 1, child: child!)
              : Positioned.fromRect(
                  rect: rect,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(18),
                    child: ColoredBox(color: Colors.black, child: child),
                  ),
                ),
          child: web,
        ),
      ],
    );
  }
}

/// Marks where the video should appear (e.g. in place of the cover in Now
/// Playing). The stage follows it every frame, through page transitions.
class VideoSlot extends StatefulWidget {
  const VideoSlot({super.key});

  @override
  State<VideoSlot> createState() => _VideoSlotState();
}

class _VideoSlotState extends State<VideoSlot>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final _engine = YouTubeEngine.instance;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker((_) => _report())..start();
  }

  void _report() {
    final box = context.findRenderObject() as RenderBox?;
    // Only while this page is on top: sheets and dialogs over it hide it.
    final onTop = ModalRoute.of(context)?.isCurrent ?? true;
    Rect? rect;
    if (box != null && box.hasSize && box.attached && onTop) {
      rect = box.localToGlobal(Offset.zero) & box.size;
    }
    if (rect != null) _engine._slotOwner = this;
    if (_engine._slotOwner == this && rect != _engine.slot.value) {
      _engine.slot.value = rect;
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    // Give the slot up after this frame, unless another took it meanwhile.
    final engine = _engine;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (engine._slotOwner != this) return;
      engine._slotOwner = null;
      engine.slot.value = null;
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      const AspectRatio(aspectRatio: 16 / 9, child: SizedBox.expand());
}
