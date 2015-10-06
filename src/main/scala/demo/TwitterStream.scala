package demo

import twitter4j.StatusAdapter
import twitter4j.Status
import twitter4j.TwitterStreamFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.stream.OverflowStrategy
import twitter4j.FilterQuery
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import scala.util.Try


object Util {
  import akka.stream.scaladsl.Source
  import akka.stream.scaladsl.ZipWith
  import akka.stream.scaladsl.FlowGraph

  implicit class WithZip[I1, M1](lhs: Source[I1, M1]) {
    def zip[I2, M2](rhs: Source[I2, M2]): Source[(I1, I2), M1] = {
      Source(lhs) { implicit b =>
        l =>
          import FlowGraph.Implicits._
          val z = b.add(ZipWith[I1, I2, (I1, I2)]((l, r) => l -> r))
          l ~> z.in0
          rhs ~> z.in1
          z.out
      }
    }

    def atRate(rate: FiniteDuration): Source[I1, M1] = atRate(0 millis, rate)

    def atRate(initial: FiniteDuration, rate: FiniteDuration): Source[I1, M1] =
      zip(Source(initial, rate, ())).map { case (i, _) => i }
  }

}

object TwitterStream {

  val file_conf = ConfigFactory.load();
  
  // Need to have Twitter credentials 
  // google is your friend eg https://www.prophoto.com/support/twitter-api-credentials/
  
  // Put credentials in src/main/resources/application.conf eg
  //OAuthConsumerKey = "..."
  //OAuthConsumerSecret = "..."
  //OAuthAccessToken = "..."
  //OAuthAccessTokenSecret = "..."

  val config = new twitter4j.conf.ConfigurationBuilder()
    .setOAuthConsumerKey(file_conf.getString("OAuthConsumerKey"))
    .setOAuthConsumerSecret(file_conf.getString("OAuthConsumerSecret"))
    .setOAuthAccessToken(file_conf.getString("OAuthAccessToken"))
    .setOAuthAccessTokenSecret(file_conf.getString("OAuthAccessTokenSecret"))
    .build

  // case classes to use Spray Json to parse the Json coming from google  
  case class GResult(url: String)
  object GResult { implicit val fmt = jsonFormat1(GResult.apply) }
  case class GResponseData(results: Seq[GResult])
  object GResponseData { implicit val fmt = jsonFormat1(GResponseData.apply) }
  case class GSearch(responseData: GResponseData)
  object GSearch { implicit val fmt = jsonFormat1(GSearch.apply) }


  def googleSearch(s: String)(implicit as: ActorSystem, mat: Materializer, ec: ExecutionContext): Future[(String, String)] = {
    val search_uri = "https://ajax.googleapis.com/ajax/services/search/web?v=1.0&q=" + s
    
    // Use Akka Http client interface to do a single Http Get and "unmarshall" the response
    Http().singleRequest(HttpRequest(uri = search_uri)).flatMap(Unmarshal(_).to[String]).map { json =>
      // Content type is text/javascript rather than application/json so can't use to[GSearch] in line above
      // which would be the normal way to automatically unmarshall
      // akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport is hard coded to expect application/json
      // so just parse the string directly as a workaround
      val opt_gs = Try { json.parseJson.convertTo[GSearch] }.toOption
      
      val first = opt_gs.flatMap { _.responseData.results.lift(0).map(_.url) }.getOrElse("NO GOOGLE RESULT for " + s)
      
      s -> first
    }
  }

  def main(args: Array[String]) {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher
    import Util._

    val twitterStream = new TwitterStreamFactory(config).getInstance;

    // Use an Actor to integrate the callback interface of twitter with Akka Stream
    // Use mapMaterializedValue to get access to the ActorRef when the Source is materialized
    val src = Source.actorRef[String](1000, OverflowStrategy.dropHead).mapMaterializedValue { a_ref =>
      twitterStream.addListener(new StatusAdapter {
        override def onStatus(status: Status) { a_ref ! status.getText }
      })
    }

    val q = "afl"
    
    val top_3_words_in_minute = src.
      groupedWithin(5000, 60 seconds).  // Creates a Source[Seq[String], _] - gets a Seq[String] representing 1 minute worth of tweets
      mapConcat { tweets =>             // Creates a Source[String, _] - mapConcat means the element in thetop3 sequence appear individually
        // Get a word count map with hashtags stripped off and words 5 or more letters
        val word_count = tweets.flatMap(_.toLowerCase.split("\\s+")).
          map { s => val parts = s.split("#", 2); if (parts.length == 2) parts(1) else s }.
          filter { s => s == q || s.length >= 5 }.
          groupBy(identity).
          mapValues(_.length)
        // extract the top 3 words (by count) from the map
        val top3 = word_count.toList.sortBy { case (w, c) => (-c, w) }.map(p => p._1).take(3)
        top3
      }

    val fut = top_3_words_in_minute.
      atRate(15 seconds). // atRate is defined in Util at top of file - ensures slow querying of google
      mapAsync(1)(googleSearch).  // mapAsync is handy for dealing with Future's
      take(8).
      runWith( Sink.foreach(println) )
      
    // The runWith above causes materialization and gives the rightmost Materialized Value which is Future[Unit]
    // Future[Unit] is used to know when stream has ended for cleanup

    twitterStream.filter(new FilterQuery().track(q).language("en"))

    fut onComplete { u =>
      twitterStream.cleanUp
      twitterStream.shutdown
      system.terminate
    }

  }
}