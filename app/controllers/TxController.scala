package controllers

import javax.inject.Inject

import Exceptions._
import akka.stream.scaladsl.{ JsonFraming, Keep, Sink }
import config.AppExecutionContexts
import persistence.querylang.{ BooleanExpr, QueryParser }
import persistence.{ FeatureWriter, Repository }
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import utilities.Utils

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

/**
 * @author Karel Maesen, Geovise BVBA
 *         creation-date: 7/25/13
 */
class TxController @Inject() (val repository: Repository) extends FeatureServerController {

  import AppExecutionContexts.streamContext

  def insert(db: String, col: String) = {
    val parser = mkJsonWritingBodyParser(db, col)
    Action(parser) {
      request => Ok(s"Written ${request.body} features")
    }
  }

  private def parse(js: JsValue): Try[BooleanExpr] = js match {
    case JsString(s) => QueryParser.parse(s)
    case _ => Failure(new RuntimeException("Query expression is not a string"))
  }

  def remove(db: String, col: String) = RepositoryAction {
    implicit request =>
      {
        extract[JsString](request.body.asJson, "query") flatMap (parse(_)) match {
          case Success(q) => repository.delete(db, col, q)
            .map(_ => Ok("Objects removed"))
            .recover(commonExceptionHandler(db))
          case Failure(e) => Future.successful(BadRequest(s"Invalid parameters: ${e.getMessage} "))
        }
      }
  }

  def update(db: String, col: String) = RepositoryAction {
    implicit request =>
      {
        val tq = extract[JsString](request.body.asJson, "query").flatMap(parse(_))
        val td = extract[JsObject](request.body.asJson, "update")
        (tq, td) match {
          case (Success(q), Success(d)) =>
            repository
              .update(db, col, q, d)
              .map(n => Ok(s"n objects updated."))
              .recover(commonExceptionHandler(db))
          case _ => Future.successful(BadRequest(s"Invalid Request body"))
        }
      }
  }

  def upsert(db: String, col: String) = RepositoryAction {
    implicit request =>
      request.body.asJson match {
        case Some(obj: JsObject) => repository.upsert(db, col, obj)
          .map(_ => Ok("Objects upserted"))
          .recover(commonExceptionHandler(db))
        case _ => Future.successful(BadRequest(s"No Json object in request body."))
      }
  }

  private def extract[T <: JsValue: ClassTag](in: Option[JsValue], key: String): Try[T] =
    Try {
      in.map {
        js => (js \ key).getOrElse(JsNull)
      } match {
        case Some(v: T) => v
        case _ => throw new InvalidParamsException(s"Request body $in isn't a Json with an  $key property of correct type")
      }
    }

  /**
   * Creates a new BodyParser that deserializes GeoJson features in the input and
   * writes them to the specified database and collection.
   *
   * @param db the database to write features to
   * @param col the collection to write features to
   * @return a new BodyParser
   */
  private def mkJsonWritingBodyParser(db: String, col: String): BodyParser[Int] = {
    val writer = repository.writer(db, col)
    bodyParser(writer, config.Constants.chunkSeparator)
  }

  private def bodyParser(writer: FeatureWriter, sep: String) = BodyParser("GeoJSON feature BodyParser") { request =>
    {
      //TODO -- the "magic" numbers should be documented and configurable.
      //TODO -- Better to refactor FeatureWriter to a Sink, and have factory method for that Sink in the Repository
      val flow = JsonFraming.objectScanner(1024 * 1024)
        .map(_.utf8String)
        .map(s => Utils.withInfo(s"seen: $s") { s })
        .map { str => Json.parse(str) }
        .collect { case js: JsObject => js } //TODO -- log where there is a parse failure
        .grouped(128)
        .mapAsync(2)(group => writer.add(group)) //TODO -- better error-handling, using
        .toMat(Sink.fold(0)(_ + _))(Keep.right)

      Accumulator(flow).map(Right.apply)
    }
  }

}

