import 'package:flutter/material.dart';

/// Lets any screen or sheet open a page inside the current tab, so the mini
/// player and nav bar stay visible (closing Now Playing first if needed).
final shellNavigator = ValueNotifier<NavigatorState? Function()?>(null);

void openPage(BuildContext context, Widget page) {
  final root = Navigator.of(context, rootNavigator: true);
  root.popUntil((r) => r.isFirst);
  final nav = shellNavigator.value?.call() ?? Navigator.of(context);
  nav.push(MaterialPageRoute(builder: (_) => page));
}
