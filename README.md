# akka-stream-demo
Some demo code for Akka Stream

The code is mostly for looking at and using as a reference since it doesn't really do anything useful

To run the TwitterStream program you need to have Twitter credentials

google is your friend eg https://www.prophoto.com/support/twitter-api-credentials/
  
Then put credentials in src/main/resources/application.conf eg
```
OAuthConsumerKey = "..."
OAuthConsumerSecret = "..."
OAuthAccessToken = "..."
OAuthAccessTokenSecret = "..."
```
To run just use sbt:

sbt run 

The DemoHttpServer doesn't do much but show how you can get a http endpoint up and going with a minimal amount of code

After running you can can point your browser at http://localhost:8081/hello


