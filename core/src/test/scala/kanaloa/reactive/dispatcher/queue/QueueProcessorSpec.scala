package kanaloa.reactive.dispatcher.queue

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorRefFactory, PoisonPill, Props}
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{TestActorRef, TestActor, TestProbe}
import kanaloa.reactive.dispatcher.ApiProtocol.{QueryStatus, ShutdownSuccessfully, WorkFailed, WorkTimedOut}
import kanaloa.reactive.dispatcher.metrics.Metric
import kanaloa.reactive.dispatcher.queue.QueueProcessor.{ScaleTo, Shutdown, ShuttingDown, WorkCompleted}
import kanaloa.reactive.dispatcher.{Backend, ResultChecker, SpecWithActorSystem}
import org.scalatest.concurrent.Eventually

import scala.collection.mutable.{Map ⇒ MMap}
import scala.concurrent.Future
import scala.concurrent.duration._

class QueueProcessorSpec extends SpecWithActorSystem with Eventually {

  type QueueTest = (TestActorRef[QueueProcessor], TestProbe, TestProbe, TestBackend, TestWorkerFactory) ⇒ Any

  def withQueueProcessor(poolSettings: ProcessingWorkerPoolSettings = ProcessingWorkerPoolSettings())(test: QueueTest) {

    val queueProbe = TestProbe("queue")
    val testBackend = new TestBackend()
    val testWorkerFactory = new TestWorkerFactory()
    val metricsCollector = TestProbe("metrics-collector")
    val qp = TestActorRef[QueueProcessor](QueueProcessor.default(queueProbe.ref, testBackend, poolSettings, metricsCollector.ref, None, testWorkerFactory)(SimpleResultChecker))
    watch(qp)
    try {
      test(qp, queueProbe, metricsCollector, testBackend, testWorkerFactory)
    } finally {
      unwatch(qp)
      qp.stop()
    }
  }

  //very specific for my needs here, but we can def generalize this if need be
  implicit class HelpedTestProbe(probe: TestProbe) {

    def setAutoPilotPF(pf: PartialFunction[Any, AutoPilot]): Unit = {
      probe.setAutoPilot(
        new AutoPilot {
          override def run(sender: QueueRef, msg: Any): AutoPilot = pf.applyOrElse(msg, (x: Any) ⇒ TestActor.NoAutoPilot)
        }
      )
    }
  }

  "The QueueProcessor" should {

    "create Workers on startup" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒
      qp.underlyingActor.workerPool should have size 5
      testBackend.timesInvoked shouldBe 5
    }

    "scale workers up" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒
      qp ! ScaleTo(10)
      eventually {
        qp.underlyingActor.workerPool should have size 10
        testBackend.timesInvoked shouldBe 10
      }
    }

    "scale workers down" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒

      qp ! ScaleTo(4) //kill 1 Worker

      eventually {
        workerFactory.retiredCount.get() shouldBe 1
      }

      //pick any 2 actors, since the QueueProcessor is not currently tracking who got the term signal
      //kill the 'Workers' who got the Retire message, so that they signal the QP to remove them
      workerFactory.probeMap.values.take(1).foreach(_.ref ! PoisonPill)

      eventually {
        qp.underlyingActor.workerPool should have size 4
      }
      //just to be safe(to make sure that some other Retire messages didn't sneak by after we reached 2 earlier)
      workerFactory.retiredCount.get shouldBe 1
    }

    "honor minimum pool size during AutoScale" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒
      qp ! ScaleTo(1) //minimum is 3, starts at 5

      eventually {
        workerFactory.retiredCount.get() shouldBe 2
      }

      workerFactory.probeMap.values.take(2).foreach(_.ref ! PoisonPill)

      eventually {
        qp.underlyingActor.workerPool should have size 3
      }
    }

    "honor maximum pool size during AutoScale" in
      withQueueProcessor(ProcessingWorkerPoolSettings(maxPoolSize = 7)) { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒
        qp ! ScaleTo(10) //maximum is 7

        eventually {
          qp.underlyingActor.workerPool should have size 7
          testBackend.timesInvoked shouldBe 7
        }
      }

    "attempt to keep the number of Workers at the minimumWorkers" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒
      //current workers are 5, minimum workers are 3, so killing 4 should result in 2 new recreate attempts
      workerFactory.probeMap.keys.take(4).foreach(workerFactory.killAndRemoveWorker)
      eventually {
        qp.underlyingActor.workerPool should have size 3
        testBackend.timesInvoked shouldBe 7 //2 new invocations should have happened
        workerFactory.probeMap should have size 3 //should only be 3 workers
      }
    }

    "shutdown Queue and wait for Workers to terminate" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒

      qp ! Shutdown(Some(self), 30.seconds)
      queueProbe.expectMsg(Queue.Retire(30.seconds))

      qp ! QueryStatus()
      expectMsg(ShuttingDown)

      //when the Queue is told to shutDown, it will send
      //term signals to Workers.  Workers will then eventually terminate
      //These PoisonPills simulate that

      workerFactory.probeMap.values.foreach { probe ⇒
        probe.ref ! PoisonPill
      }

      expectMsg(ShutdownSuccessfully)
      expectTerminated(qp)
    }

    "shutdown if Queue terminates" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒

      queueProbe.ref ! PoisonPill

      eventually {
        workerFactory.retiredCount.get() shouldBe 5 //all workers should receive a Retire signal
      }

      qp ! QueryStatus()
      expectMsg(ShuttingDown)

      //simulate the Workers all finishing up
      workerFactory.probeMap.values.foreach { probe ⇒
        probe.ref ! PoisonPill
      }

      expectTerminated(qp)

    }

    "force shutdown if timeout" in withQueueProcessor() { (qp, queueProbe, metricsCollector, testBackend, workerFactory) ⇒

      qp ! Shutdown(Some(self), 25.milliseconds)
      queueProbe.expectMsg(Queue.Retire(25.milliseconds))
      //We wn't kill the Workers, and the timeout should kick in
      expectTerminated(qp) //should force itself to shutdown
    }
  }

  class TestBackend extends Backend {
    val probe = TestProbe()
    var timesInvoked: Int = 0

    override def apply(f: ActorRefFactory): Future[ActorRef] = {
      timesInvoked += 1
      Future.successful(probe.ref)
    }
  }

  class TestWorkerFactory extends WorkerFactory {

    val probeMap: MMap[ActorRef, TestProbe] = MMap()

    val retiredCount: AtomicInteger = new AtomicInteger(0)

    //create a Worker, and increment a count when its told to Retire.
    override def createWorker(
      queueRef:               QueueRef,
      routee:                 QueueRef,
      metricsCollector:       ActorRef,
      circuitBreakerSettings: Option[CircuitBreakerSettings],
      resultChecker:          ResultChecker,
      workerName:             String
    )(implicit ac: ActorRefFactory): ActorRef = {
      val probe = TestProbe(workerName)
      probe.setAutoPilotPF {
        case Worker.Retire ⇒
          retiredCount.incrementAndGet()
          //probe.ref
          TestActor.NoAutoPilot
      }
      probeMap += (probe.ref → probe)
      probe.ref
    }

    def killAndRemoveWorker(ref: ActorRef) {
      probeMap.remove(ref)
      ref ! PoisonPill
    }
  }

}
