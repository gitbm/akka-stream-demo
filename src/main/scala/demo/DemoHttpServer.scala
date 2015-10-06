package demo

import scala.io.StdIn

import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.actor.ActorSystem


object DemoHttpServer {

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()

    val route =
      path("hello") {
        get {
          complete ("Hello !!")
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8081)

    println(s"Server online at http://localhost:8081/\nPress RETURN to stop...")
    StdIn.readLine()

    import system.dispatcher // for the future transformations
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.terminate) // and shutdown when done
  }
}
