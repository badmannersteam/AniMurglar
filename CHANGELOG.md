# Полный отчёт о внесённых изменениях

## 1. Исправление Koin квалификаторов для HttpClient
**Файл:** `AppModule.kt`

Два `single { httpClient(...) }` имели одинаковый тип, Koin не мог различить. Добавлены квалификаторы:
- `named("proxy")` — проксирующий клиент
- `named("direct")` — прямой клиент

Все `get()` вызовы в шлюзах обновлены с правильными квалификаторами.

## 2. Кэширование HTTP-ответов Nyaa
**Файл:** `NyaaGateway.kt`

`loadDocument()` обёрнут в `searchCache.getOrPut()` — HTML-ответы кэшируются по URL. TTL configurable через `config.cache.nyaaCacheTtlDays`.

## 3. Гео-блокировка Kodik → fallback на direct
**Файл:** `KodikGateway.kt`

Гео-блокировка теперь детектируется **внутри** `executeWithProxyFallback` блока в `loadText()`. Когда прокси возвращает заблокированную страницу — бросается исключение, триггерится fallback на direct.

Упрощён `extractPlayerScriptPath` — убраны избыточные проверки на гео-блок (старая логика отбрасывала 20k+ символьные валидные страницы Kodik).

## 4. qBittorrent без прокси
**Файлы:** `TorrentDownloadService.kt`, `AppModule.kt`

`TorrentDownloadService` принимает `directClient`. `QBittorrentClient` использует его для `localhost:8080` — торрент-трафик никогда не идёт через SOCKS5.

## 5. Устойчивость DownloadCoordinator
**Файл:** `DownloadCoordinator.kt`

`torrentJob.await()` обёрнут в `try-catch` — ошибка торрента не убивает загрузку дабов и субтитров.

## 6. Конфиг кэшей (TTL)
**Файл:** `AppConfig.kt`

Добавлен `CacheConfig`:
```kotlin
@Serializable
data class CacheConfig(
    val searchCacheTtlDays: Long = 3,
    val searchHistoryTtlDays: Long = 30,
    val nyaaCacheTtlDays: Long = 1,
)
```

Прокидывается в `SearchCache`, `SearchHistory`, `NyaaGateway`.

## 7. Кэш поиска (единый файл)
**Файл:** `SearchCache.kt`

Вместо сотен мелких файлов — один `search-cache.json`. `ConcurrentHashMap<String, CacheEntry>` с `ReadWriteLock`. TTL configurable.

**Интегрирован в:**
- `ShikimoriGateway.search()` — ключ `shikimori:{query}:{limit}`
- `AnimeLibDubsSource.searchAnime()` — ключ `animelib:{query}`
- `YummyAnimeDubsSource.searchAnime()` — ключ `yummy:{query}`
- `Anime365SubtitlesGateway.loadTeams()` — ключ `anime365:{query}`
- `NyaaGateway.loadDocument()` — ключ `nyaa:{url}`

## 8. Кэширование загрузки озвучек
**Файлы:** `AnimeLibDubsSource.kt`, `YummyAnimeDubsSource.kt`

`loadDubs()` обёрнут в `searchCache.getOrPut()`:
- AnimeLib: ключ `animelib:dubs:{id}`
- YummyAnime: ключ `yummy:dubs:{id}`

## 9. История поиска (Suggestions)
**Файл:** `SearchHistory.kt`

JSON-файл `search-history.json`, max 50 записей, TTL configurable. Методы:
- `suggestions(query)` — фильтрация по подстроке
- `recent(limit)` — последние запросы

**Интеграция:**
- `ShikimoriStore` — при активации поиска показываются последние запросы; при вводе — фильтрация; при очистке — снова recent
- `ShikimoriScreen` — UI под строкой поиска

## 10. UI поиска Shikimori
**Файл:** `ShikimoriScreen.kt`

- Кнопка `✕` (свернуть) — Unicode `\u25BC` (▼)
- Кнопка очистки текста — Unicode `\u2715` (✕), видна только при непустом запросе
- Кнопка `Найти` — по центру
- Карточка выбранного тайтла — кнопка `✕` для сброса выбора

**Файл:** `ShikimoriStore.kt`

Добавлен `Intent.SelectionCleared` / `Message.SelectionCleared` — сбрасывает `selectedAnimeId`, `searchResults`, `query`.

## 11. Объединённый build-скрипт
**Файлы:** `build.bat`, `build.sh`

`build.bat` — меню: [1] Сборка без ProGuard [2] ZIP без ProGuard [3] ZIP с ProGuard. С `pause` в конце.
`build.sh` — Linux/macOS аналог с `read`.

## 12. Очистка ZIP-архивов
**Файл:** `build.gradle.kts`

`zipAppImage` и `zipReleaseAppImage` исключают `temp/`, `output/`, `logs/`.

## 13. encodeDefaults=true
**Файл:** `AppConfig.kt`

JSON-сериализация теперь кодирует значения по умолчанию — `config.json` содержит реальные значения вместо пустых объектов.

## 14. Логирование запросов
**Файл:** `RequestLogger.kt`

Утилита `RequestLogger` с методами:
- `logRequest(url, method, status, chars, via)`
- `logCacheHit(key)` / `logCacheMiss(key)` / `logCacheWrite(key)`

**Файл:** `log4j2.xml`

Отдельный appender `RequestAppender` → файл `requests.log`.

**Интегрировано в:**
- `SearchCache` — логирует hit/miss/write
- `NyaaGateway.loadDocument()` — логирует запросы
- `AnimeLibDubsSource` — `search`, `loadEpisodes`, `loadPlayers`
- `YummyAnimeDubsSource` — `loadSearchPage`, `loadInitialPlayerUrl`, `loadPlayer`
- `ShikimoriGateway.searchInternal()`

## 15. Исправления из Qodana
| Файл | Исправление |
|------|-------------|
| `FfmpegService.kt` | `delay(1000L)` → `delay(1000.milliseconds)` |
| `FfmpegService.kt` | `forEach {` → `forEach { _ ->` (2 места) |
| `AnimeLibDubsSource.kt` | `@Suppress("UastIncorrectHttpHeaderInspection")` на `hapiHeaders()` |
| `SyncDebugCli.kt` | Уникализированы похожие логи: `[SYNC_CLI:analyze]`, `syncedPath=` |
| `settings.gradle.kts` | `@file:Suppress("UnstableApiUsage")` |
| `ShikimoriScreen.kt` | Удалена зависимость `compose.materialIconsExtended`, иконки → Unicode |

## 16. @Serializable аннотации
Добавлены для кэширования:
- `ShikimoriAnime`, `DubAnimeCandidate`, `DubInfo`, `DubEpisode`, `ResolvedDubEpisode`, `SubtitleTeamInfo`, `SubtitleEpisode`
