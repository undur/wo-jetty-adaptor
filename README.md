## 🔌 wo-jetty-adaptor

A Jetty-based WOAdaptor. Uses Jetty's servlet-free APIs meaning it's relatively lightweigt.

## Why?

* I want websockets.
* The default adaptor for `ng-objects` is Jetty-based, so a Jetty-based `WOAdaptor` theoretically allows me to serve `WO` and `ng` from the same application / jetty server instance. _And_ I can share the WebSockets logic between frameworks.

## Usage

Build/install, add as a dependency to your application and pass in the argument `-WOAdaptor WOAdaptorJetty`

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>wo-jetty-adaptor</artifactId>
	<version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Does this actually work?

Yes. Since November 12. 2025 it's been deployed on a couple of sites, some of which see quite a bit of traffic.

## Performance

Running the following locally on my MacBook M4 to serve a simple ~230kb application resource:

```
ab -k -n 10000 -c 16 http://127.0.0.1:1200/Apps/WebObjects/Hugi.woa/res/app/ZillaSlab-Light.ttf
```

Returns the following results. So pretty great I'd say.

Disabling `KeepAlive` (removing the `-k` parameter) slows us down by ~50%.

```
Concurrency Level:      16
Time taken for tests:   0.692 seconds
Complete requests:      10000
Failed requests:        0
Keep-Alive requests:    10000
Total transferred:      2399870000 bytes
HTML transferred:       2398360000 bytes
Requests per second:    14460.19 [#/sec] (mean)
Time per request:       1.106 [ms] (mean)
Time per request:       0.069 [ms] (mean, across all concurrent requests)
Transfer rate:          3388922.70 [Kbytes/sec] received
```

## Future plans

* Implement a websocket API
* Get a life outside of WO/ng development