from pathlib import Path

path = Path('android/app/src/main/java/com/scrolller/adblock/RedViewV2Activity.kt')
text = path.read_text()

old = '''            Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (controller.selectedTab) {
                        MainTab.HOME -> V2GalleryScreen(controller, "funny", "Home", true)
                        MainTab.FAVORITES -> V2FavoritesScreen(controller)
                        MainTab.SEARCH -> V2SearchScreen(controller)
                        MainTab.SETTINGS -> V2SettingsScreen(controller)
                    }
                }
            }'''

new = '''            Scaffold(bottomBar = { V2BottomBar(controller) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    // Keep every root tab composed. Inactive tabs receive a zero-size
                    // layout, so their remember/LazyList/search state survives without
                    // receiving touches or drawing behind the active tab.
                    Box(Modifier.fillMaxSize(if (controller.selectedTab == MainTab.HOME) 1f else 0f)) {
                        V2GalleryScreen(controller, "funny", "Home", true)
                    }
                    Box(Modifier.fillMaxSize(if (controller.selectedTab == MainTab.FAVORITES) 1f else 0f)) {
                        V2FavoritesScreen(controller)
                    }
                    Box(Modifier.fillMaxSize(if (controller.selectedTab == MainTab.SEARCH) 1f else 0f)) {
                        V2SearchScreen(controller)
                    }
                    Box(Modifier.fillMaxSize(if (controller.selectedTab == MainTab.SETTINGS) 1f else 0f)) {
                        V2SettingsScreen(controller)
                    }
                }
            }'''

count = text.count(old)
if count != 1:
    raise SystemExit(f'persistent root tabs: expected exactly 1 match, found {count}')

text = text.replace(old, new, 1)
path.write_text(text)
print('RedView V4 persistent root-tab patch applied successfully')
