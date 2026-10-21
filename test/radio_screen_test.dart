import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:musicly/models/track.dart';
import 'package:musicly/services/radio_api.dart';
import 'package:musicly/state/library_controller.dart';
import 'package:musicly/state/player_controller.dart';
import 'package:musicly/ui/icons.dart';
import 'package:musicly/ui/radio_screen.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class FakeRadio extends RadioApi {
  String? requestedCountry;

  @override
  Future<List<Track>> top({int limit = 30}) async {
    requestedCountry = null;
    return [];
  }

  @override
  Future<List<Track>> byCountry(String code, {int limit = 40}) async {
    requestedCountry = code;
    return [];
  }

  @override
  Future<List<Map>> fetchCountries() async => [
    {'name': 'Canada', 'iso_3166_1': 'CA', 'stationcount': 12},
    {'name': 'Japan', 'iso_3166_1': 'JP', 'stationcount': 20},
  ];
}

class TestLibrary extends LibraryController {
  final _radio = FakeRadio();

  @override
  FakeRadio get radio => _radio;

  @override
  Future<void> loadHome() async {}
}

class FakePlayer extends ChangeNotifier implements PlayerController {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  testWidgets(
    'country search stays above keyboard and favorites become chips',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      tester.view.physicalSize = const Size(400, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      final lib = TestLibrary();
      await tester.pumpWidget(
        MultiProvider(
          providers: [
            ChangeNotifierProvider<LibraryController>.value(value: lib),
            ChangeNotifierProvider<PlayerController>(
              create: (_) => FakePlayer(),
            ),
          ],
          child: const MaterialApp(home: RadioScreen()),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byIcon(AppIcons.globe), findsOneWidget);
      await tester.tap(find.byTooltip('Select country'));
      await tester.pumpAndSettle();
      final search = find.widgetWithText(TextField, 'Search countries...');
      await tester.tap(search);
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.enterText(search, 'Japan');
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(
        tester.getBottomLeft(find.widgetWithText(ListTile, 'Japan')).dy,
        lessThan(500),
      );
      final sheetTop = tester.getTopLeft(find.text('Select Country')).dy;
      await tester.enterText(search, 'no match');
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('Select Country')).dy, sheetTop);
      await tester.enterText(search, 'Japan');
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Favorite Japan'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('Unfavorite Japan'), findsOneWidget);
      await tester.tap(find.widgetWithText(ListTile, 'Japan'));
      tester.view.resetViewInsets();
      await tester.pumpAndSettle();
      expect(lib.radio.requestedCountry, 'JP');
      final chips = tester
          .widgetList<ChoiceChip>(find.byType(ChoiceChip))
          .toList();
      expect((chips[1].label as Text).data, contains('Japan'));
      expect(chips[1].selected, isTrue);
      await tester.tap(find.text('Near you'));
      await tester.pumpAndSettle();
      expect(lib.radio.requestedCountry, isNull);
      await lib.save();
      final prefs = await SharedPreferences.getInstance();
      expect(jsonDecode(prefs.getString('radio_countries')!), {'JP': 'Japan'});
      final restored = TestLibrary();
      await tester.pumpAndSettle();
      expect(restored.favoriteRadioCountries, {'JP': 'Japan'});
      restored.toggleFavoriteRadioCountry('JP', 'Japan');
      await restored.save();
      expect(jsonDecode(prefs.getString('radio_countries')!), isEmpty);
      await tester.pump(const Duration(seconds: 21));
    },
  );
}
