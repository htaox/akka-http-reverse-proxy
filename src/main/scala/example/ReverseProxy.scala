package example

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Host, `Content-Type`, `Raw-Request-URI`}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.ConfigFactory
import io.circe._
import models._

import scala.concurrent.Future

class ReverseProxy {

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http = Http(system)

  val config = ConfigFactory.load()
  val useFlow = if (config.hasPath("useFlow")) config.getBoolean("useFlow") else false
  val counter = new AtomicInteger(0)
  val AbsoluteUri = """(?is)^(https?)://([^/]+)(/.*|$)""".r

  val services: Map[String, Seq[Target]] = Map(
    "test.foo.bar" -> Seq(
      Target("http://127.0.0.1:8081"),
      Target("http://127.0.0.1:8082"),
      Target("http://127.0.0.1:8083")
    )
  )

  def flow(host: String, port: Int): Flow[HttpRequest, HttpResponse, Any] = http.outgoingConnection(host, port)

  def uriString(request: HttpRequest): String = request.header[`Raw-Request-URI`].map(_.uri).getOrElse(request.uri.toRelative.toString())

  def host(request: HttpRequest): String = {
    uriString(request) match {
      case AbsoluteUri(_, hostPort, _) => hostPort
      case _                           => request.header[Host].map(_.host.address()).getOrElse("")
    }
  }

  def handler(request: HttpRequest): Future[HttpResponse] = {
    services.get(host(request)) match {
      case Some(seq) => {
        val index = counter.incrementAndGet() % (if (seq.nonEmpty) seq.size else 1)
        val target = seq.apply(index)
        val inCtype: ContentType = request
          .header[`Content-Type`]
          .map(_.contentType)
          .getOrElse(ContentTypes.`application/octet-stream`)
        val headersIn: Seq[HttpHeader] = request.headers.filterNot(t => t.name() == "Host" || t.name() == "Timeout-Access") :+ Host(target.host)
        val proxyRequest = HttpRequest(
            method = request.method,
            uri = Uri(
              scheme = target.scheme,
              authority = Authority(host = Uri.NamedHost(target.host), port = target.port),
              path = request.uri.toRelative.path,
              queryString = request.uri.toRelative.rawQueryString,
              fragment = request.uri.toRelative.fragment
            ),
            headers = headersIn.toList,
            entity = HttpEntity(inCtype, request.entity.dataBytes),
            protocol = HttpProtocols.`HTTP/1.1`
        )
        if (useFlow) {
          Source.single(proxyRequest).via(flow(target.host, target.port)).runWith(Sink.head)
        } else {
          http.singleRequest(proxyRequest)
        }
      }
      case None => Future.successful {
        HttpResponse(
          404,
          entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString("Not found")).noSpaces)
        )
      }
    }        
  }

  def start(host: String, port: Int) {
    http.bindAndHandleAsync(handler, host, port)
  }
}
