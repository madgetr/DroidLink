package dev.droidlink

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** What a route handler returns. */
sealed class Response
class Json(val body: JSONObject, val code: Int = 200) : Response()
class Bytes(val contentType: String, val data: ByteArray, val headers: Map<String, String> = emptyMap()) : Response()
/** Long-lived response (e.g. MJPEG). The writer runs until it returns or the client disconnects. */
class Stream(val contentType: String, val writer: (OutputStream) -> Unit) : Response()

/** Thrown by handlers for "can't do that right now" (HTTP 503), e.g. no droid connected. */
class Unavailable(message: String) : Exception(message)

/**
 * A deliberately tiny HTTP/1.1 server: GET requests with query parameters in,
 * JSON / bytes / streams out, one request per connection. No third-party deps.
 */
class HttpServer(
    private val port: Int,
    private val handler: (path: String, params: Map<String, String>) -> Response,
) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = s
        thread(name = "http-accept", isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { serve(client) }
                } catch (e: IOException) {
                    break // socket closed
                }
            }
        }
        Log.i(TAG, "listening on :$port")
    }

    fun stop() {
        socket?.close()
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) { // skip headers
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val target = requestLine.split(" ").getOrElse(1) { "/" }
                val uri = Uri.parse("http://localhost$target")
                val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

                val response = try {
                    handler(uri.path ?: "/", params)
                } catch (e: IllegalArgumentException) {
                    Json(JSONObject().put("detail", e.message), 400)
                } catch (e: Unavailable) {
                    Json(JSONObject().put("detail", e.message), 503)
                } catch (e: Exception) {
                    Log.w(TAG, "request failed: $target", e)
                    Json(JSONObject().put("detail", e.toString()), 500)
                }
                write(c.getOutputStream(), response)
            } catch (e: IOException) {
                // client went away mid-response (normal for streams)
            }
        }
    }

    private fun write(out: OutputStream, r: Response) {
        when (r) {
            is Json -> {
                val bytes = r.body.toString().toByteArray()
                out.write(head(r.code, "application/json", bytes.size, emptyMap()))
                out.write(bytes)
            }
            is Bytes -> {
                out.write(head(200, r.contentType, r.data.size, r.headers))
                out.write(r.data)
            }
            is Stream -> {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${r.contentType}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
                r.writer(out)
            }
        }
        out.flush()
    }

    private fun head(code: Int, type: String, length: Int, headers: Map<String, String>): ByteArray {
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: $length\r\n")
            .append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        return sb.append("\r\n").toString().toByteArray()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error"
    }

    companion object { private const val TAG = "HttpServer" }
}
