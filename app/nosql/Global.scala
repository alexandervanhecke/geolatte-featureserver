package nosql

import kamon.Kamon
import org.slf4j.LoggerFactory
import play.api._
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.Future

object Global extends GlobalSettings {


  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  override def onError(request: RequestHeader, ex: Throwable) = {
    val useful = ex match {
      case e: UsefulException => e
      case _ => UnexpectedException(unexpected = Some(ex))
    }
    Future { InternalServerError(views.html.defaultpages.error(useful)) }
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = {
    Future { NotFound(s"Request ${request.path} not found.") }
  }

//  Kamon.metrics.entity()
//  lazy val metricRegistry = new {}
//
//  lazy val jmxReporter = JmxReporter.forRegistry(metricRegistry).build()
//
//  lazy val logReporter = Slf4jReporter.forRegistry(metricRegistry)
//    .outputTo(LoggerFactory.getLogger("metrics"))
//    .convertRatesTo(TimeUnit.SECONDS)
//    .convertDurationsTo(TimeUnit.MILLISECONDS)
//    .build()
//
//  def startMetrics(app: Application) : Unit = {
//    if (app.mode == Mode.Prod) {
//      jmxReporter.start()
//      logReporter.start(1, TimeUnit.MINUTES)
//    }
//  }
//
//  def stopMetrics(app: Application) : Unit = {
//    if (app.mode == Mode.Prod) {
//      jmxReporter.stop()
//      logReporter.stop()
//    }
//  }


  override def onStart(app: Application) {
      Kamon.start()
  }

  val requestLogger = LoggerFactory.getLogger("requests")

  val loggingFilter = Filter { (nextFilter, requestHeader) =>
    val startTime = System.currentTimeMillis

    nextFilter(requestHeader).map { result =>
      val endTime = System.currentTimeMillis
      val requestTime = endTime - startTime
//      val timer = metricRegistry.timer(s"requests.${requestHeader.path.tail.replaceAll("/", ".")}.${requestHeader.method.toLowerCase}")
//      timer.update(requestTime, TimeUnit.MILLISECONDS)
      requestLogger.info(s"${requestHeader.method} ${requestHeader.uri} ; $requestTime ; ${result.header.status}")
      result.withHeaders("Request-Time" -> requestTime.toString)

    }
  }

  override def onStop(app: Application): Unit = {
    Kamon.shutdown()
    //TODO also stop repository connection pools (e.g. postgresql-async!)
  }


}



