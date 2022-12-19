package de.marmaro.krt.ffupdater.network

import android.net.TrafficStats
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import de.marmaro.krt.ffupdater.network.exceptions.ApiRateLimitExceededException
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.settings.NetworkSettingsHelper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import ru.gildor.coroutines.okhttp.await
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import kotlin.reflect.KMutableProperty0


class FileDownloader(private val networkSettingsHelper: NetworkSettingsHelper) {
    private val trafficStatsThreadId = 10001

    var onProgress: (progressInPercent: Int?, totalMB: Long) -> Unit = @WorkerThread { _, _ -> }

    // fallback to wait for the download when the activity was recreated
    var currentDownload: Deferred<Any>? = null

    // It is safe to reuse the OkHttpClient -> Don't recreate it every callUrl() execution
    private val okHttpClient = createClient()

    @MainThread
    @Throws(NetworkException::class)
    suspend fun downloadBigFileAsync(url: String, file: File): Deferred<Any> {
        return withContext(Dispatchers.IO) {
            currentDownload = async {
                try {
                    lastChange = System.currentTimeMillis()
                    numberOfRunningDownloads.incrementAndGet()
                    downloadBigFileInternal(url, file)
                } catch (e: IOException) {
                    throw NetworkException("Download of $url failed.", e)
                } catch (e: IllegalArgumentException) {
                    throw NetworkException("Download of $url failed.", e)
                } catch (e: NetworkException) {
                    throw NetworkException("Download of $url failed.", e)
                } finally {
                    lastChange = System.currentTimeMillis()
                    numberOfRunningDownloads.decrementAndGet()
                }
            }
            currentDownload!!
        }
    }

    @WorkerThread
    private suspend fun downloadBigFileInternal(url: String, file: File) {
        callUrl(url).use { response ->
            val body = validateAndReturnResponseBody(url, response)
            if (file.exists()) {
                file.delete()
            }
            file.outputStream().buffered().use { fileWriter ->
                body.byteStream().buffered().use { responseReader ->
                    // this method blocks until download is finished
                    responseReader.copyTo(fileWriter)
                }
            }
        }
    }

    /**
     * onProgress and currentDownload will stay null because the download is small.
     */
    @MainThread
    @Throws(NetworkException::class)
    suspend fun downloadSmallFileAsync(url: String): Deferred<String> {
        return withContext(Dispatchers.IO) {
            async {
                try {
                    downloadSmallFileInternal(url)
                } catch (e: IOException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                } catch (e: IllegalArgumentException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                } catch (e: NetworkException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                }
            }
        }
    }

    @WorkerThread
    private suspend fun downloadSmallFileInternal(url: String): String {
        callUrl(url).use { response ->
            val body = validateAndReturnResponseBody(url, response)
            return body.string()
        }
    }

    private fun validateAndReturnResponseBody(url: String, response: Response): ResponseBody {
        if (url.startsWith(GITHUB_URL) && response.code == 403) {
            throw ApiRateLimitExceededException(
                "API rate limit for GitHub is exceeded.",
                Exception("response code is ${response.code}")
            )
        }
        if (!response.isSuccessful) {
            throw NetworkException("Response is unsuccessful. HTTP code: '${response.code}'.")
        }
        return response.body ?: throw NetworkException("Response is unsuccessful. Body is null.")
    }

    private suspend fun callUrl(url: String): Response {
        require(url.startsWith("https://"))
        TrafficStats.setThreadStatsTag(trafficStatsThreadId)
        val request = Request.Builder()
            .url(url)
            .build()
        val call = okHttpClient.newCall(request)
        return call.await()
    }

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()

        builder.addNetworkInterceptor { chain: Interceptor.Chain ->
            val original = chain.proceed(chain.request())
            val body = requireNotNull(original.body)
            original.newBuilder()
                .body(ProgressResponseBody(body, this::onProgress))
                .build()
        }

        createSslSocketFactory()?.let {
            builder.sslSocketFactory(it.first, it.second)
        }
        createDnsConfiguration()?.let {
            builder.dns(it)
        }
        createProxyConfiguration()?.let {
            builder.proxy(it)
        }
        createProxyAuthenticatorConfiguration()?.let {
            builder.proxyAuthenticator(it)
        }

        return builder.build()
    }

    private fun createSslSocketFactory(): Pair<SSLSocketFactory, X509TrustManager>? {
        if (!networkSettingsHelper.areUserCAsTrusted) {
            return null // use the system trust manager
        }

        // https://developer.android.com/reference/java/security/KeyStore
        val systemAndUserCAStore = KeyStore.getInstance("AndroidCAStore")
        systemAndUserCAStore.load(null)

        // https://stackoverflow.com/a/24401795
        // https://github.com/bitfireAT/davx5-ose/blob/0e93a47d6d7277d3a18e31c6528f578c467a56ea/app/src/main/java/at/bitfire/davdroid/HttpClient.kt
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(systemAndUserCAStore)

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(systemAndUserCAStore, "keystore_pass".toCharArray())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, SecureRandom())

        val trustManager = trustManagerFactory.trustManagers.first() as X509TrustManager
        return Pair(sslContext.socketFactory, trustManager)
    }

    private fun createDnsConfiguration(): Dns? {
        return when (networkSettingsHelper.dnsProvider) {
            NetworkSettingsHelper.DnsProvider.SYSTEM -> null
            NetworkSettingsHelper.DnsProvider.DIGITAL_SOCIETY_SWITZERLAND_DOH -> digitalSocietySwitzerlandDoH
            NetworkSettingsHelper.DnsProvider.QUAD9_DOH -> quad9DoH
            NetworkSettingsHelper.DnsProvider.CLOUDFLARE_DOH -> cloudflareDoH
            NetworkSettingsHelper.DnsProvider.GOOGLE_DOH -> googleDoH
            NetworkSettingsHelper.DnsProvider.CUSTOM_SERVER -> createDnsConfigurationFromUserInput()
            NetworkSettingsHelper.DnsProvider.NO -> fakeDnsResolver
        }
    }

    private fun createDnsConfigurationFromUserInput(): DnsOverHttps {
        val customServer = networkSettingsHelper.customDohServer()
        return createDnsOverHttpsResolver(customServer.host, customServer.ips)
    }

    private fun createProxyConfiguration(): Proxy? {
        val proxy = networkSettingsHelper.proxy() ?: return null
        return Proxy(proxy.type, InetSocketAddress.createUnresolved(proxy.host, proxy.port))
    }

    private fun createProxyAuthenticatorConfiguration(): Authenticator? {
        val proxy = networkSettingsHelper.proxy() ?: return null
        val username = proxy.username ?: return null
        val password = proxy.password
            ?: throw IllegalArgumentException("Invalid proxy configuration. You have to specify a password.")
        return ProxyAuthenticator(username, password)
    }

    // simple communication between WorkManager and the InstallActivity to prevent duplicated downloads
    // persistence/consistence is not very important -> global available variables are ok
    companion object {
        private var numberOfRunningDownloads = AtomicInteger(0)
        private var lastChange = System.currentTimeMillis()
        fun areDownloadsCurrentlyRunning() = (numberOfRunningDownloads.get() != 0) &&
                ((System.currentTimeMillis() - lastChange) < 3600_000)

        const val GITHUB_URL = "https://api.github.com"

        // https://github.com/square/okhttp/blob/7768de7baaa992adcd384871cb8720873f6b8fd0/okhttp-dnsoverhttps/src/test/java/okhttp3/dnsoverhttps/DnsOverHttpsTest.java
        // sharing an OkHttpClient is safe
        private val bootstrapClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
        }

        // https://de.wikipedia.org/wiki/DNS_over_HTTPS
        // https://github.com/DigitaleGesellschaft/DNS-Resolver
        val digitalSocietySwitzerlandDoH by lazy {
            createDnsOverHttpsResolver(
                url = "https://dns.digitale-gesellschaft.ch/dns-query",
                ips = listOf("2a05:fc84::42", "2a05:fc84::43", "185.95.218.42", "185.95.218.43")
            )
        }

        // https://www.quad9.net/news/blog/doh-with-quad9-dns-servers/
        val quad9DoH by lazy {
            createDnsOverHttpsResolver(
                url = "https://dns.quad9.net/dns-query",
                ips = listOf("2620:fe::fe", "2620:fe::fe:9", "9.9.9.9", "149.112.112.112"),
            )
        }

        // https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/make-api-requests/
        val cloudflareDoH by lazy {
            createDnsOverHttpsResolver(
                url = "https://cloudflare-dns.com/dns-query",
                ips = listOf("1.1.1.1"),
            )
        }

        // https://developers.google.com/speed/public-dns/docs/doh
        // https://developers.google.com/speed/public-dns/docs/using#google_public_dns_ip_addresses
        val googleDoH by lazy {
            createDnsOverHttpsResolver(
                url = "https://dns.google/dns-query",
                ips = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            )
        }

        private fun createDnsOverHttpsResolver(url: String, ips: List<String>): DnsOverHttps {
            return DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .build()
        }

        val fakeDnsResolver by lazy {
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
        }
    }
}

internal class ProgressResponseBody(
    private val responseBody: ResponseBody,
    private var onProgress: KMutableProperty0<(progressInPercent: Int?, totalMB: Long) -> Unit>
) : ResponseBody() {
    override fun contentType() = responseBody.contentType()
    override fun contentLength() = responseBody.contentLength()
    override fun source() = trackTransmittedBytes(responseBody.source()).buffer()

    private fun trackTransmittedBytes(source: Source): Source {
        return object : ForwardingSource(source) {
            private val sourceIsExhausted = -1L
            var totalBytesRead = 0L
            var totalProgress = -1
            var totalMB = 0L

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead != sourceIsExhausted) {
                    totalBytesRead += bytesRead
                }
                if (contentLength() > 0L) {
                    val progress = (100 * totalBytesRead / contentLength()).toInt()
                    if (progress != totalProgress) {
                        totalProgress = progress
                        val totalMegabytesRead = totalBytesRead / 1_048_576
                        onProgress.get().invoke(progress, totalMegabytesRead)
                    }
                } else {
                    val totalMegabytesRead = totalBytesRead / 1_048_576
                    if (totalMegabytesRead != totalMB) {
                        totalMB = totalMegabytesRead
                        onProgress.get().invoke(null, totalMegabytesRead)
                    }
                }
                return bytesRead
            }
        }
    }
}
