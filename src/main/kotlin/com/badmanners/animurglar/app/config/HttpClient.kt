package com.badmanners.animurglar.app.config

import com.neutrine.krate.rateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.host
import okhttp3.OkHttpClient
import org.apache.logging.log4j.LogManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.temporal.ChronoUnit

private val logger = LogManager.getLogger("HttpClient")

fun httpClient(config: AppConfig) = HttpClient(OkHttp) {
    config.proxy.takeIf { it.enabled && it.host.isNotBlank() }?.let { proxy ->
        logger.info("Configuring proxy: {}://{}:{} (auth={})", proxy.type, proxy.host, proxy.port, proxy.username.isNotBlank())
        val javaProxyType = when (proxy.type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        val socketAddr = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
        engine {
            config {
                proxy(Proxy(javaProxyType, socketAddr))
                if (proxy.username.isNotBlank()) {
                    val credential = okhttp3.Credentials.basic(proxy.username, proxy.password)
                    addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        )
                    }
                }
            }
        }
    } ?: run {
        logger.info("No proxy configured, using direct connection")
    }

    install(HttpRequestRetry) {
        retryOnException(config.downloader.retries.coerceAtLeast(0), retryOnTimeout = true)
        constantDelay(100L, 100L)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = config.networkTimeouts.connectMillis
        socketTimeoutMillis = config.networkTimeouts.socketMillis
    }
    install(Logging) {
        level = LogLevel.NONE
    }
}.apply {

    val nyaa = rateLimiter(maxRate = 2)
    val animelib = rateLimiter(maxRate = 80) {
        maxBurst = 25
        maxRateTimeUnit = ChronoUnit.MINUTES
    }
    val yummy = rateLimiter(maxRate = 120) {
        maxBurst = 25
        maxRateTimeUnit = ChronoUnit.MINUTES
    }
    val kodik = rateLimiter(maxRate = 10) {
        maxBurst = 25
    }
    val anime365 = rateLimiter(maxRate = 120) {
        maxBurst = 25
        maxRateTimeUnit = ChronoUnit.MINUTES
    }

    plugin(HttpSend).intercept { builder ->
        when {
            builder.host.contains("nyaa.si") -> nyaa
            builder.host.contains("hapi.hentaicdn.org") -> animelib
            builder.host.contains("yummyanime.tv") -> yummy
            builder.host.contains("kodikplayer.com") -> kodik
            builder.host.contains("smotret-anime.org") -> anime365
            else -> null
        }?.awaitUntilTake()

        execute(builder)
    }
}
