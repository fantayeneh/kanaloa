package kanaloa.reactive.dispatcher

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ ImplicitSender, TestKit, TestProbe }
import com.typesafe.config.{ ConfigException, ConfigFactory }
import kanaloa.reactive.dispatcher.ApiProtocol.WorkFailed
import kanaloa.reactive.dispatcher.Backend.UnexpectedRequest
import kanaloa.reactive.dispatcher.metrics.{ NoOpMetricsCollector, StatsDMetricsCollector }
import kanaloa.reactive.dispatcher.queue.ProcessingWorkerPoolSettings
import kanaloa.reactive.dispatcher.queue.TestUtils.MessageProcessed
import org.specs2.specification.Scope

import scala.concurrent.Future

class DispatcherSpec extends SpecWithActorSystem {
  "pulling work dispatcher" should {

    "finish a simple list" in new ScopeWithActor {
      val iterator = List(1, 3, 5, 6).iterator
      val pwp = system.actorOf(Props(PullingDispatcher(
        "test",
        iterator,
        Dispatcher.defaultDispatcherSettings().copy(workerPool = ProcessingWorkerPoolSettings(1), autoScaling = None),
        backend,
        metricsCollector = NoOpMetricsCollector,
        ({ case Success ⇒ Right(()) })
      )))

      delegatee.expectMsg(1)
      delegatee.reply(Success)
      delegatee.expectMsg(3)
      delegatee.reply(Success)
      delegatee.expectMsg(5)
      delegatee.reply(Success)
      delegatee.expectMsg(6)
      delegatee.reply(Success)
    }
  }

  "pushing work dispatcher" should {
    trait SimplePushingDispatchScope extends ScopeWithActor {
      val dispatcher = system.actorOf(PushingDispatcher.props(
        name = "test",
        Backend((i: String) ⇒ Future.successful(MessageProcessed(i)))
      )(ResultChecker.simple[MessageProcessed]))
    }
    "work happily with simpleBackend" in new SimplePushingDispatchScope {

      dispatcher ! "3"
      expectMsg(MessageProcessed("3"))

    }

    "let simple backend reject unrecognized message" in new SimplePushingDispatchScope {
      dispatcher ! 3
      expectMsgType[WorkFailed]

    }

    "let simple result check fail on  unrecognized reply message" in new ScopeWithActor {
      val dispatcher = system.actorOf(PushingDispatcher.props(
        name = "test",
        Backend((i: String) ⇒ Future.successful("A Result"))
      )(ResultChecker.simple[MessageProcessed]))

      dispatcher ! "3"
      expectMsgType[WorkFailed]

    }

  }

  "readConfig" should {
    "use default settings when nothing is in config" in {
      val (settings, mc) = Dispatcher.readConfig("example", ConfigFactory.empty)
      settings.workRetry === 0
      mc === NoOpMetricsCollector
    }
    "use default-dispatcher settings when dispatcher name is missing in the dispatchers section" in {
      val cfgStr =
        """
          |kanaloa {
          |  default-dispatcher {
          |     workRetry = 27
          |  }
          |  dispatchers {
          |
          |  }
          |
          |}
        """.stripMargin

      val (settings, _) = Dispatcher.readConfig("example", ConfigFactory.parseString(cfgStr))
      settings.workRetry === 27
    }

    "parse settings that match the name" in {
      val cfgStr =
        """
          |kanaloa {
          |  dispatchers {
          |    example {
          |      circuitBreaker {
          |        errorRateThreshold = 0.5
          |      }
          |    }
          |  }
          |
          |}
        """.stripMargin

      val (settings, _) = Dispatcher.readConfig("example", ConfigFactory.parseString(cfgStr))
      settings.circuitBreaker.errorRateThreshold === 0.5
    }

    "parse statsD collector " in {
      val cfgStr =
        """
          |kanaloa {
          |  metrics {
          |    statsd {
          |      host = "localhost"
          |      eventSampleRate = 0.5
          |    }
          |  }
          |}
        """.stripMargin

      val (_, mc) = Dispatcher.readConfig("example", ConfigFactory.parseString(cfgStr))
      mc must beAnInstanceOf[StatsDMetricsCollector]
      mc.asInstanceOf[StatsDMetricsCollector].eventSampleRate === 0.5
    }

    "throw exception when host is missing" in {
      val cfgStr =
        """
          |kanaloa {
          |  metrics {
          |    statsd {
          |    }
          |  }
          |}
        """.stripMargin

      Dispatcher.readConfig("example", ConfigFactory.parseString(cfgStr)) must throwA[ConfigException]
    }
  }
}

class ScopeWithActor(implicit system: ActorSystem) extends TestKit(system) with ImplicitSender with Scope {
  case object Success

  val delegatee = TestProbe()

  val backend = Backend(delegatee.ref)
}
