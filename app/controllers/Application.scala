package controllers

import play.api._
import play.api.mvc._
import play.api.libs.ws.WS
import play.api.libs.iteratee.{Enumeratee, Enumerator, Concurrent}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.EventSource

trait Event
case class Operation(amount: Int, level: String, from: String, userid: String, timestamp: Long) extends Event
case class OperationStatus(message: String, from: String, timestamp: Long) extends Event

object Application extends Controller {

  val parisReader = (
    (__ \ "amount").read[Int] and
    (__ \ "level").read[String] and
    (__ \ "address" \ "city").read[String] and
    (__ \ "userid").read[String] and
    (__ \ "timestamp").read[Long]
  )(Operation)

  val operationFmt = Json.format[Operation]
  val operationStatusFmt = Json.format[OperationStatus]

  val eventFmt = new Format[Event] {
    def reads(json: JsValue): JsResult[Event] = json \ "amount" match {
      case _: JsUndefined => operationStatusFmt.reads(json)
      case _ => operationFmt.reads(json)
    }
    def writes(e: Event): JsValue = e match {
      case o: Operation => operationFmt.writes(o)
      case s: OperationStatus => operationStatusFmt.writes(s)
    }
  }

  def index(role: String) = Action {
    Ok(views.html.index(role))
  }

  def getStream[A <: Event](request: WSRequestHolder, reader: Reads[A]): Enumerator[Event] = {
    val (iteratee, enumerator) = Concurrent.joined[Array[Byte]]
    request.get(_ => iteratee).map(_.run)
    enumerator &> Enumeratee.map[Array[Byte]](Json.parse) ><>
      Enumeratee.map[JsValue](reader.reads) ><>
      Enumeratee.collect[JsResult[A]] {
        case JsSuccess(value, _) => value
      }
  }

  def feed(role: String, lower: Int, higher: Int) = Action {

    val lr = getStream( WS.url("http://localhost:9000/operations?from=LaRochelle").withRequestTimeout(-1), eventFmt )
    val paris = getStream( WS.url("http://localhost:9000/operations?from=Paris").withRequestTimeout(-1), parisReader )
    val nantes = getStream( WS.url("http://localhost:9000/operations?from=Nantes").withRequestTimeout(-1), eventFmt )
    val lyon = getStream( WS.url("http://localhost:9000/operations?from=Lyon").withRequestTimeout(-1), eventFmt )

    val secure: Enumeratee[Event, Event] = Enumeratee.collect[Event] {
      case s: OperationStatus if role == "MANAGER" => s
      case o@Operation(_, "public", _, _, _) => o
      case o@Operation(_, "private", _, _, _) if role == "MANAGER" => o
    }

    val inBounds: Enumeratee[Event, Event] = Enumeratee.collect[Event] {
      case s: OperationStatus => s
      case o@Operation(amount, _, _, _, _) if amount > lower && amount < higher => o
    }

    val pipeline = (lr >- paris >- nantes >- lyon)

    val transformer =
      secure ><>
      inBounds ><>
      Enumeratee.map[Event](eventFmt.writes)

    Ok.feed( pipeline.through(transformer).through( EventSource() ) ).as("text/event-stream")
  }

}