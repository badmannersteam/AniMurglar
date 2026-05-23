package com.badmanners.animurglar.app.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.app.config.httpClient
import com.badmanners.animurglar.downloader.DirectDownloader
import com.badmanners.animurglar.downloader.DownloadCoordinator
import com.badmanners.animurglar.downloader.DubDownloadService
import com.badmanners.animurglar.downloader.HlsDownloader
import com.badmanners.animurglar.downloader.SubtitleDownloadService
import com.badmanners.animurglar.downloader.TorrentDownloadService
import com.badmanners.animurglar.dubs.AnimeLibDubsSource
import com.badmanners.animurglar.dubs.DubsGateway
import com.badmanners.animurglar.dubs.KodikGateway
import com.badmanners.animurglar.dubs.YummyAnimeDubsSource
import com.badmanners.animurglar.ffmpeg.FfmpegService
import com.badmanners.animurglar.ffmpeg.SyncAnalyzeService
import com.badmanners.animurglar.ffmpeg.SyncChartService
import com.badmanners.animurglar.nyaa.NyaaGateway
import com.badmanners.animurglar.shikimori.ShikimoriGateway
import com.badmanners.animurglar.subtitles.Anime365SubtitlesGateway
import com.badmanners.animurglar.subtitles.SubtitleCaptionFilterService
import com.badmanners.animurglar.ui.downloader.DownloaderStore
import com.badmanners.animurglar.ui.downloader.DownloaderStoreFactory
import com.badmanners.animurglar.ui.dubs.DubsPickerStore
import com.badmanners.animurglar.ui.dubs.DubsPickerStoreFactory
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStoreFactory
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStoreFactory
import com.badmanners.animurglar.ui.processing.ProcessingStore
import com.badmanners.animurglar.ui.processing.ProcessingStoreFactory
import com.badmanners.animurglar.ui.root.RootStore
import com.badmanners.animurglar.ui.root.RootStoreFactory
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore
import com.badmanners.animurglar.ui.shikimori.ShikimoriStoreFactory
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStoreFactory
import org.koin.dsl.module

fun appModule(config: AppConfig) = module {

    single { config }

    single { httpClient(config) }

    single { NyaaGateway(get()) }

    single { ShikimoriGateway(get()) }

    single { KodikGateway(get()) }

    single { AnimeLibDubsSource(get(), get()) }
    single { YummyAnimeDubsSource(get(), get()) }

    single { DubsGateway(listOf(get<AnimeLibDubsSource>(), get<YummyAnimeDubsSource>())) }

    single { Anime365SubtitlesGateway(get()) }
    single { SubtitleCaptionFilterService() }

    single<StoreFactory> { DefaultStoreFactory() }

    single<ShikimoriStore> { ShikimoriStoreFactory(get(), get()).create() }
    single<NyaaPickerStore> { NyaaPickerStoreFactory(get(), get()).create() }
    single<DubsPickerStore> { DubsPickerStoreFactory(get(), get()).create() }
    single<SubtitlesPickerStore> { SubtitlesPickerStoreFactory(get(), get()).create() }
    single<EpisodeMappingStore> { EpisodeMappingStoreFactory(get()).create() }
    single<DownloaderStore> { DownloaderStoreFactory(get(), get()).create() }
    single<ProcessingStore> { ProcessingStoreFactory(get(), get(), get(), get(), get()).create() }

    single<RootStore> { RootStoreFactory(get(), get(), get(), get(), get(), get(), get(), get(), get()).create() }

    single { FfmpegService() }
    single { SyncAnalyzeService(get()) }
    single { SyncChartService() }
    single { TorrentDownloadService(get(), get()) }
    single { DirectDownloader(get(), get()) }
    single { HlsDownloader(get(), get(), get()) }
    single { DubDownloadService(get(), get(), get(), get()) }
    single { SubtitleDownloadService(get(), get(), get()) }
    single { DownloadCoordinator(get(), get(), get(), get()) }
}

