package controllers

import play.api.mvc.{Action, Controller}
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{Json, JsObject}
import play.api.libs.concurrent.Promise
import java.util.concurrent.TimeUnit

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Random

object OperationWS extends Controller {

  def operations(from: String) = Action {
    val enumerator = Enumerator.generateM[JsObject](
      Promise.timeout(Some(
        Json.obj(
          "timestamp" -> System.currentTimeMillis(),
          "level" -> (if(Random.nextBoolean) "public" else "private"),
          "userid" -> s"John-Doe-${Random.nextInt(1000)}",
          "from" -> from,
          "amount" -> Random.nextInt(1000)
        )
      ), 500 + Random.nextInt(500), TimeUnit.MILLISECONDS)
    )
    val enumeratorParis = Enumerator.generateM[JsObject](
      Promise.timeout(Some(
        Json.obj(
          "timestamp" -> System.currentTimeMillis(),
          "level" -> (if(Random.nextBoolean) "public" else "private"),
          "userid" -> s"John-Doe-${Random.nextInt(1000)}",
          "address" -> Json.obj(
            "city" -> from,
            "street" -> s"${Random.nextInt(10)} rue du blah"
          ),
          "amount" -> Random.nextInt(1000)
        )
      ), 500 + Random.nextInt(500), TimeUnit.MILLISECONDS)
    )
    val noise = Enumerator.generateM[JsObject](
      Promise.timeout(Some(
        Json.obj(
          "timestamp" -> System.currentTimeMillis(),
          "from" -> from,
          "message" -> "System ERROR"
        )
      ), 2000 + Random.nextInt(2000), TimeUnit.MILLISECONDS)
    )
    from match {
      case "Paris" => Ok.chunked( enumeratorParis >- noise )
      case _ => Ok.chunked( enumerator >- noise )
    }
  }

}
