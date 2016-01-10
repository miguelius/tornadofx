package tornadofx

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.scene.control.ProgressBar
import javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS
import javafx.scene.control.Tooltip
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.*
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.*
import org.apache.http.message.BasicHeader
import org.apache.http.util.EntityUtils
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import javax.json.Json
import javax.json.JsonArray
import javax.json.JsonObject
import javax.json.JsonValue

class Rest : Controller() {
    private val atomicseq = AtomicLong()
    internal var ongoingRequests = FXCollections.observableArrayList<HttpRequestBase>()

    var baseURI: String? = null
        set(value) {
            val uri = URI.create(value)

            field = uri.path

            if (uri.host != null) {
                val scheme = if (uri.scheme == null) "http" else uri.scheme
                val port = if (uri.port > -1) uri.port else if (scheme == "http") 80 else 443
                this.host = HttpHost(uri.host, port, scheme)
            }
        }

    var host: HttpHost? = null
    lateinit var client: CloseableHttpClient
    lateinit var clientContext: HttpClientContext
    var credentialsProvider: CredentialsProvider? = null

    init {
        resetClientContext()
        configure()
    }

    fun configure(builderConfigurator: (HttpClientBuilder) -> Unit) {
        val builder = clientBuilder
        builderConfigurator(builder)
        client = builder.build()
    }

    fun configure() {
        client = clientBuilder.build()
    }

    private val clientBuilder: HttpClientBuilder
        get() = HttpClients.custom().setDefaultRequestConfig(defaultRequestConfig).setDefaultCredentialsProvider(credentialsProvider)

    private val defaultRequestConfig: RequestConfig
        get() = RequestConfig.custom().build()

    fun resetClientContext() {
        clientContext = HttpClientContext.create()
    }

    fun setBasicAuth(username: String, password: String) {
        val credsProvider = BasicCredentialsProvider()

        credsProvider.setCredentials(
                AuthScope(host),
                UsernamePasswordCredentials(username, password))

        val authCache = BasicAuthCache()
        authCache.put(host, BasicScheme())
        clientContext.authCache = authCache

        credentialsProvider = credsProvider

        configure()
    }

    fun get(path: String) = execute(HttpGet(getURI(path)))

    fun put(path: String, data: JsonValue? = null) = execute(HttpPut(getURI(path)), data)
    fun put(path: String, data: JsonModel) = put(path, Json.createObjectBuilder().apply { data.toJSON(this) }.build())

    fun post(path: String, data: JsonValue? = null) = execute(HttpPost(getURI(path)), data)
    fun post(path: String, data: JsonModel) = post(path, Json.createObjectBuilder().apply { data.toJSON(this) }.build())

    fun delete(path: String, data: JsonValue? = null) = execute(HttpDelete(getURI(path)), data)
    fun delete(path: String, data: JsonModel) = delete(path, Json.createObjectBuilder().apply { data.toJSON(this) }.build())

    fun getURI(path: String): URI {
        try {
            val uri = StringBuilder()

            if (baseURI != null)
                uri.append(baseURI)

            if (uri.toString().endsWith("/") && path.startsWith("/"))
                uri.append(path.substring(1))
            else if (!uri.toString().endsWith("/") && !path.startsWith("/"))
                uri.append("/").append(path)
            else
                uri.append(path)

            return URI(uri.toString().replace(" ", "%20"))
        } catch (ex: URISyntaxException) {
            throw RuntimeException(ex)
        }

    }

    private fun execute(request: HttpRequestBase, data: JsonValue? = null): HttpResponse {
        val seq = atomicseq.addAndGet(1)

        try {
            if (data != null && request is HttpEntityEnclosingRequestBase) {
                request.setHeader(BasicHeader("Content-Type", "application/json"))
                request.entity = StringEntity(data.toString(), StandardCharsets.UTF_8)
            }

            ongoingRequests.add(request)
            val response = client.execute(host, request, clientContext)
            response.addHeader("X-Seq", seq.toString())
            return response
        } finally {
            ongoingRequests.remove(request)
        }
    }

}

fun HttpResponse.consume() = EntityUtils.consume(entity)

fun HttpResponse.toByteArray() = EntityUtils.toByteArray(entity)

fun HttpResponse.inputStream() = entity.content

fun HttpResponse.one(): JsonObject {
    try {
        val content = text()

        if (content == null || content.isEmpty())
            return Json.createObjectBuilder().build()

        val json = Json.createReader(StringReader(content)).use { it.read() }

        return when (json) {
            is JsonArray -> {
                if (json.isEmpty())
                    return Json.createObjectBuilder().build()
                else
                    return json.getJsonObject(0)
            }
            is JsonObject -> json
            else -> throw IllegalArgumentException("Unknown json result value")
        }
    } finally {
        EntityUtils.consume(entity)
    }
}

inline fun <reified T : JsonModel> JsonObject.toModel(): T {
    val model = T::class.java.newInstance()
    model.updateModel(this)
    return model
}

inline fun <reified T : JsonModel> JsonArray.toModel(): ObservableList<T> {
    return FXCollections.observableArrayList(map { (it as JsonObject).toModel<T>() })
}

fun HttpResponse.ok() = statusCode == 200

val HttpResponse.statusCode: Int get() = statusLine.statusCode

val HttpResponse.reason: String get() = statusLine.reasonPhrase

fun HttpResponse.text() = EntityUtils.toString(entity, StandardCharsets.UTF_8)

fun HttpResponse.list(): JsonArray {
    try {
        val content = text()

        if (content == null || content.isEmpty())
            return Json.createArrayBuilder().build()

        val json = Json.createReader(StringReader(content)).use { it.read() }

        return when (json) {
            is JsonArray -> json
            is JsonObject -> Json.createArrayBuilder().add(json).build()
            else -> throw IllegalArgumentException("Unknown json result value")
        }
    } finally {
        EntityUtils.consume(entity)
    }
}

class RestProgressBar : View() {
    override val root = ProgressBar().apply {
        prefWidth = 100.0
        isVisible = false
    }

    private val api: Rest by inject()

    init {
        api.ongoingRequests.addListener(ListChangeListener<HttpRequestBase> { c ->
            val size = c.list.size

            Platform.runLater {
                val tooltip = c.list.map{ r -> "%s %s".format(r.method, r.uri) }.joinToString("\n")

                root.tooltip = Tooltip(tooltip)
                root.isVisible = size > 0

                if (size == 0) {
                    root.progress = 100.0
                } else if (size == 1) {
                    root.progress = INDETERMINATE_PROGRESS
                } else {
                    val pct = 1.0 / size.toDouble()
                    root.progress = pct
                }
            }
        })
    }

}
