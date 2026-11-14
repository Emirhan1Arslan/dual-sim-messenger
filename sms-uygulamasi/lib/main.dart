import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'platform.dart';
import 'screens/home_screen.dart';
import 'screens/setup_screen.dart';
import 'store.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    systemNavigationBarColor: Colors.transparent,
    systemNavigationBarContrastEnforced: false,
  ));
  store.init();
  runApp(const CiftSimApp());
}

class CiftSimApp extends StatefulWidget {
  const CiftSimApp({super.key});

  @override
  State<CiftSimApp> createState() => _CiftSimAppState();
}

class _CiftSimAppState extends State<CiftSimApp> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Ayarlardan dönüldüğünde rol/izin ve SIM durumu değişmiş olabilir.
    if (state == AppLifecycleState.resumed && store.known) store.refreshStatus();
  }

  ThemeData _theme(Brightness brightness) => ThemeData(
        useMaterial3: true,
        // Android 12+ cihazlarda sistemin dinamik vurgu rengi, eskilerde SIM 1 mavisi.
        colorScheme: ColorScheme.fromSeed(
          seedColor: store.seedColor ?? SimInfo.sim1Color,
          brightness: brightness,
        ),
      );

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) => MaterialApp(
        title: 'Çift SIM Mesajlar',
        debugShowCheckedModeBanner: false,
        theme: _theme(Brightness.light),
        darkTheme: _theme(Brightness.dark),
        home: !store.known
            ? const Scaffold(body: Center(child: CircularProgressIndicator()))
            : store.setupDone
                ? const HomeScreen()
                : const SetupScreen(),
      ),
    );
  }
}
