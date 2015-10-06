name := "akka-stream-demo"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
	"com.typesafe.akka" % "akka-actor_2.11" % "2.4.0-RC3",
	"com.typesafe.akka" % "akka-http-experimental_2.11" % "1.0",
	"com.typesafe.akka" % "akka-http-spray-json-experimental_2.11" % "1.0",
	"io.spray" % "spray-json_2.11" % "1.3.2",
	"org.twitter4j" % "twitter4j-stream" % "4.0.4"
)

