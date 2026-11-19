package dev.chungjungsoo.gptmobile.data.pairing

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI

/** Keep platform trust and the original network policy for every other origin. */
@OptIn(InternalAPI::class)
internal object PairedGatewayEngine : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine {
        val base = OkHttp.create(block)
        return object : HttpClientEngine by base {
            private val paired = mutableMapOf<String, Pair<OwnerCredential, HttpClientEngine>>()

            override fun install(client: HttpClient) = super<HttpClientEngine>.install(client)

            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val url = data.url
                val entry = if (url.protocol.name == "https") OwnerGateway.find("https://${url.host}:${url.port}") else null
                val engine = if (entry == null) {
                    base
                } else {
                    synchronized(paired) {
                        val old = paired[entry.origin]
                        if (old?.first == entry) {
                            old.second
                        } else {
                            old?.second?.close()
                            OkHttp.create {
                                block()
                                config { OwnerGateway.configure(this, entry.pin, entry) }
                            }.also { paired[entry.origin] = entry to it }
                        }
                    }
                }
                return engine.execute(data)
            }

            override fun close() {
                synchronized(paired) {
                    paired.values.forEach { it.second.close() }
                    paired.clear()
                }
                base.close()
            }
        }
    }
}
